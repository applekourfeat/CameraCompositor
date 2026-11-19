package dev.cameracompositor

import android.graphics.Matrix
import android.util.Size
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot

data class TrackedFrame(val corners: List<ScenePoint>, val confidence: Float, val timeMs: Long,
                        val verified: Boolean = true)
data class TrackingEvent(val planId: Long, val frame: TrackedFrame?)

/** Continuous flow, anchored image reacquisition, and a short-lived orientation estimate. */
class SceneTracker : AutoCloseable {
    companion object { init {
        check(OpenCVLoader.initLocal()) { "Scene tracking library could not load" }
        Core.setNumThreads(2)
    } }

    private data class Seed(val frame: ScanFrame, val corners: List<ScenePoint>, val id: Long)
    private data class Descriptors(val points: List<Point>, val data: Mat) : AutoCloseable {
        override fun close() = data.release()
    }
    private data class KeyFrame(val image: Descriptors, val target: List<Point>)
    private data class Tracks(val key: KeyFrame, val origin: List<Point>, val live: List<Point>)
    private data class Verified(val tracks: Tracks, val rawTarget: List<Point>, val frame: TrackedFrame)
    private data class RotationAnchor(val corners: List<ScenePoint>, val attitude: ViewAttitude)

    private val nextId = AtomicLong()
    private val pending = AtomicReference<Seed?>()
    private val resetRequested = AtomicBoolean(false)
    private val references = mutableListOf<KeyFrame>()
    private var links: Tracks? = null
    private var previous = Mat()
    private var width = 0
    private var height = 0
    private var lastMatchAt = 0L
    private var lastKeyAt = 0L
    private var lastKeyTarget: List<ScenePoint>? = null
    private var rotationAnchor: RotationAnchor? = null
    private var orb: ORB? = null
    private var matcher: BFMatcher? = null
    var activePlanId = 0L
        private set

    fun select(frame: ScanFrame, corners: List<ScenePoint>): Long {
        val id = nextId.incrementAndGet()
        pending.set(Seed(frame, corners, id))
        return id
    }

    fun reset() { pending.set(null); resetRequested.set(true) }

    fun update(bytes: ByteArray, width: Int, height: Int, transform: Matrix, size: Size, now: Long,
               attitude: ViewAttitude? = null): TrackedFrame? {
        if (resetRequested.getAndSet(false)) clear()
        pending.getAndSet(null)?.let { initialize(it, now) }
        if (references.isEmpty() || width != this.width || height != this.height) return null

        val current = Mat(height, width, CvType.CV_8UC1).apply { put(0, 0, bytes) }
        try {
            var measured = if (!previous.empty()) links?.let { follow(current, it) }
                ?.let { verify(it, transform, size, now) } else null
            if (measured == null && now - lastMatchAt >= 110) {
                lastMatchAt = now
                measured = reacquire(current, transform, size, now)
            }
            links = measured?.tracks
            current.copyTo(previous)
            if (measured != null) {
                if (attitude != null) rotationAnchor = RotationAnchor(measured.frame.corners, attitude)
                maybeCheckpoint(current, measured, now)
                return measured.frame
            }
            val anchor = rotationAnchor ?: return null
            val estimate = attitude?.let { RotationGuide.estimate(anchor.corners, anchor.attitude, it) } ?: return null
            return TrackedFrame(estimate, 0f, now, verified = false)
        } finally {
            current.release()
        }
    }

    private fun initialize(seed: Seed, now: Long) {
        clear()
        activePlanId = seed.id
        val frame = seed.frame
        width = frame.width
        height = frame.height
        val inverse = Matrix()
        if (!frame.bufferToView.invert(inverse)) return
        val corners = seed.corners
        val pixels = corners.flatMap { listOf(it.x * frame.viewSize.width, it.y * frame.viewSize.height) }.toFloatArray()
        inverse.mapPoints(pixels)
        val target = List(4) { Point(pixels[it * 2].toDouble(), pixels[it * 2 + 1].toDouble()) }
        previous.create(height, width, CvType.CV_8UC1)
        previous.put(0, 0, frame.gray)
        val key = KeyFrame(describe(previous), target)
        references.add(key)
        links = seedFlow(previous, key)
        lastKeyAt = now
        lastKeyTarget = corners
        frame.attitude?.let { rotationAnchor = RotationAnchor(corners, it) }
    }

    private fun seedFlow(gray: Mat, key: KeyFrame): Tracks {
        val corners = MatOfPoint()
        val source = try {
            Imgproc.goodFeaturesToTrack(gray, corners, 180, .009, 9.0)
            corners.toArray().toList()
        } finally { corners.release() }
        return Tracks(key, source, source)
    }

    private fun describe(gray: Mat): Descriptors {
        val detector = orb ?: ORB.create(560).also { orb = it }
        val keys = MatOfKeyPoint()
        val mask = Mat()
        val data = Mat()
        try {
            detector.detectAndCompute(gray, mask, keys, data)
            return Descriptors(keys.toArray().map { it.pt }, data)
        } catch (error: Exception) {
            data.release()
            throw error
        } finally { keys.release(); mask.release() }
    }

    private fun follow(current: Mat, track: Tracks): Tracks? {
        if (track.live.size < 12) return null
        val from = MatOfPoint2f(*track.live.toTypedArray())
        val to = MatOfPoint2f(); val back = MatOfPoint2f()
        val status = MatOfByte(); val reverse = MatOfByte()
        val errors = MatOfFloat(); val backErrors = MatOfFloat()
        try {
            Video.calcOpticalFlowPyrLK(previous, current, from, to, status, errors)
            Video.calcOpticalFlowPyrLK(current, previous, to, back, reverse, backErrors)
            val next = to.toArray()
            val returned = back.toArray()
            val good = status.toArray()
            val backGood = reverse.toArray()
            val error = errors.toArray()
            val keep = track.live.indices.filter { i ->
                good[i].toInt() != 0 && backGood[i].toInt() != 0 && error[i] < 38f &&
                    hypot(track.live[i].x - returned[i].x, track.live[i].y - returned[i].y) < 1.65 &&
                    next[i].x in 0.0..(width - 1).toDouble() && next[i].y in 0.0..(height - 1).toDouble()
            }
            return Tracks(track.key, keep.map { track.origin[it] }, keep.map { next[it] })
        } finally { listOf(from, to, back, status, reverse, errors, backErrors).forEach { it.release() } }
    }

    private fun reacquire(gray: Mat, transform: Matrix, size: Size, now: Long): Verified? = describe(gray).use { observed ->
        if (observed.data.rows() < 12) return@use null
        val search = matcher ?: BFMatcher.create(Core.NORM_HAMMING, false).also { matcher = it }
        for (key in references.asReversed()) {
            if (key.image.data.rows() < 12) continue
            val neighbors = mutableListOf<MatOfDMatch>()
            val candidates = try {
                search.knnMatch(key.image.data, observed.data, neighbors, 2)
                neighbors.mapNotNull { row ->
                    val near = row.toArray()
                    if (near.size == 2 && near[0].distance <= 63 && near[0].distance < .80f * near[1].distance) near[0] else null
                }.sortedBy { it.distance }.distinctBy { it.trainIdx }.take(180)
            } finally { neighbors.forEach { it.release() } }
            val tracks = Tracks(key, candidates.map { key.image.points[it.queryIdx] },
                candidates.map { observed.points[it.trainIdx] })
            verify(tracks, transform, size, now)?.let { return@use it }
        }
        null
    }

    private fun verify(tracks: Tracks, transform: Matrix, size: Size, now: Long): Verified? {
        if (tracks.live.size < 12) return null
        val source = MatOfPoint2f(*tracks.origin.toTypedArray())
        val destination = MatOfPoint2f(*tracks.live.toTypedArray())
        val mask = Mat()
        val target = MatOfPoint2f(*tracks.key.target.toTypedArray())
        val output = MatOfPoint2f()
        val h = Calib3d.findHomography(source, destination, Calib3d.RANSAC, 3.0, mask)
        try {
            if (h.empty() || mask.empty()) return null
            val flags = ByteArray(mask.rows())
            mask.get(0, 0, flags)
            val inliers = tracks.live.indices.filter { flags[it].toInt() != 0 }
            val confidence = inliers.size.toFloat() / tracks.live.size
            if (inliers.size < 12 || confidence < .63f) return null
            val anchors = inliers.map { tracks.origin[it] }
            if (anchors.maxOf { it.x } - anchors.minOf { it.x } < width * .12 ||
                anchors.maxOf { it.y } - anchors.minOf { it.y } < height * .12) return null
            Core.perspectiveTransform(target, output, h)
            val raw = output.toArray().toList()
            val mapped = raw.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray()
            transform.mapPoints(mapped)
            val corners = List(4) { ScenePoint(mapped[it * 2] / size.width, mapped[it * 2 + 1] / size.height) }
            if (!ShotGeometry.valid(corners)) return null
            return Verified(Tracks(tracks.key, anchors, inliers.map { tracks.live[it] }), raw,
                TrackedFrame(corners, confidence, now))
        } finally { listOf(source, destination, mask, target, output, h).forEach { it.release() } }
    }

    private fun maybeCheckpoint(gray: Mat, verified: Verified, now: Long) {
        val last = lastKeyTarget ?: return
        if (now - lastKeyAt < 650 || verified.frame.confidence < .76f || verified.tracks.live.size < 24) return
        val center = ShotGeometry.center(verified.frame.corners)
        val previousCenter = ShotGeometry.center(last)
        if (hypot(center.x - previousCenter.x, center.y - previousCenter.y) < .08f &&
            abs(ShotGeometry.width(verified.frame.corners) / ShotGeometry.width(last) - 1f) < .14f) return
        val key = KeyFrame(describe(gray), verified.rawTarget)
        if (key.image.points.size < 24) { key.image.close(); return }
        references.add(key)
        if (references.size > 3) references.removeAt(1).image.close()
        links = seedFlow(gray, key)
        lastKeyTarget = verified.frame.corners
        lastKeyAt = now
    }

    private fun clear() {
        references.forEach { it.image.close() }
        references.clear()
        previous.release(); previous = Mat()
        links = null; lastMatchAt = 0; lastKeyAt = 0; lastKeyTarget = null
        rotationAnchor = null
        activePlanId = 0
    }

    override fun close() { clear(); orb?.clear(); matcher?.clear() }
}
