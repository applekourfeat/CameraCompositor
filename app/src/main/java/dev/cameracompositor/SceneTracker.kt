package dev.cameracompositor

import android.graphics.Matrix
import android.util.Size
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.video.Video
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot

data class TrackedFrame(val corners: List<ScenePoint>, val confidence: Float, val timeMs: Long)
data class TrackingEvent(val planId: Long, val frame: TrackedFrame?)

/** Track the selected scene, using optical flow plus feature matching for reacquisition. */
class SceneTracker : AutoCloseable {
    companion object { init {
        check(OpenCVLoader.initLocal()) { "Scene tracking library could not load" }
        Core.setNumThreads(2)
    } }
    private data class Seed(val frame: ScanFrame, val crop: CropOption, val id: Long)
    private val nextId = AtomicLong()
    var activePlanId = 0L
        private set
    private val pending = AtomicReference<Seed?>(null)
    @Volatile private var resetRequested = false
    private var orb: ORB? = null
    private var reference = Mat()
    private var referenceDescriptors = Mat()
    private var referenceKeys = MatOfKeyPoint()
    private var previous = Mat()
    private var originPoints = emptyList<Point>()
    private var livePoints = emptyList<Point>()
    private var cropPoints = MatOfPoint2f()
    private var lastMatchMs = 0L
    private var seedWidth = 0
    private var seedHeight = 0

    fun select(frame: ScanFrame, crop: CropOption): Long {
        val id = nextId.incrementAndGet()
        pending.set(Seed(frame, crop, id))
        return id
    }
    fun reset() { pending.set(null); resetRequested = true }

    fun update(bytes: ByteArray, width: Int, height: Int, transform: Matrix, size: Size, now: Long): TrackedFrame? {
        if (resetRequested) { clear(); resetRequested = false }
        val seed = pending.getAndSet(null)
        if (seed != null) {
            clear()
            activePlanId = seed.id
            if (orb == null) { check(OpenCVLoader.initLocal()); orb = ORB.create(500) }
            seedWidth = seed.frame.width; seedHeight = seed.frame.height
            reference = Mat(seedHeight, seedWidth, CvType.CV_8UC1).apply { put(0, 0, seed.frame.gray) }
            val mask = Mat()
            try { orb!!.detectAndCompute(reference, mask, referenceKeys, referenceDescriptors) } finally { mask.release() }
            val viewToBuffer = Matrix()
            if (!seed.frame.bufferToView.invert(viewToBuffer)) return null
            val points = ShotGeometry.corners(seed.crop.box).flatMap { listOf(it.x * seed.frame.viewSize.width,
                it.y * seed.frame.viewSize.height) }.toFloatArray()
            viewToBuffer.mapPoints(points)
            cropPoints.fromArray(*Array(4) { Point(points[it * 2].toDouble(), points[it * 2 + 1].toDouble()) })
        }
        if (reference.empty() || width != seedWidth || height != seedHeight) return null
        val current = Mat(height, width, CvType.CV_8UC1).apply { put(0, 0, bytes) }
        try {
            if (!previous.empty() && livePoints.size >= 12) flow(current)
            if (livePoints.size < 12 || now - lastMatchMs > 1200) {
                match(current)
                lastMatchMs = now
            }
            previous.release(); previous = current.clone()
            if (livePoints.size < 12) return null
            val source = MatOfPoint2f(*originPoints.toTypedArray())
            val destination = MatOfPoint2f(*livePoints.toTypedArray())
            val inliers = Mat()
            val homography = Calib3d.findHomography(source, destination, Calib3d.RANSAC, 3.0, inliers)
            try {
                val good = if (inliers.empty()) 0 else Core.countNonZero(inliers)
                val confidence = good.toFloat() / livePoints.size
                if (homography.empty() || good < 10 || confidence < .65f) { livePoints = emptyList(); return null }
                val flags = ByteArray(inliers.rows())
                inliers.get(0, 0, flags)
                val anchors = originPoints.filterIndexed { i, _ -> flags[i].toInt() != 0 }
                if (anchors.maxOf { it.x } - anchors.minOf { it.x } < width * .15 ||
                    anchors.maxOf { it.y } - anchors.minOf { it.y } < height * .15) return null
                val output = MatOfPoint2f()
                try {
                    Core.perspectiveTransform(cropPoints, output, homography)
                    val mapped = output.toArray().flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray()
                    transform.mapPoints(mapped)
                    val points = List(4) { ScenePoint(mapped[it * 2] / size.width, mapped[it * 2 + 1] / size.height) }
                    return if (ShotGeometry.valid(points)) TrackedFrame(points, confidence, now) else null
                } finally { output.release() }
            } finally { source.release(); destination.release(); inliers.release(); homography.release() }
        } finally { current.release() }
    }

    private fun flow(current: Mat) {
        val from = MatOfPoint2f(*livePoints.toTypedArray())
        val to = MatOfPoint2f(); val back = MatOfPoint2f()
        val status = MatOfByte(); val backStatus = MatOfByte()
        val errors = MatOfFloat(); val backErrors = MatOfFloat()
        try {
            Video.calcOpticalFlowPyrLK(previous, current, from, to, status, errors)
            Video.calcOpticalFlowPyrLK(current, previous, to, back, backStatus, backErrors)
            val old = from.toArray(); val next = to.toArray(); val returned = back.toArray()
            val valid = status.toArray(); val backward = backStatus.toArray(); val error = errors.toArray()
            val keep = old.indices.filter { valid[it].toInt() != 0 && backward[it].toInt() != 0 && error[it] < 30f &&
                hypot(old[it].x - returned[it].x, old[it].y - returned[it].y) < 1.5 }
            originPoints = keep.map { originPoints[it] }; livePoints = keep.map { next[it] }
        } finally { listOf(from, to, back, status, backStatus, errors, backErrors).forEach { it.release() } }
    }

    private fun match(current: Mat) {
        val keys = MatOfKeyPoint(); val descriptors = Mat(); val matches = MatOfDMatch(); val mask = Mat()
        val matcher = BFMatcher.create(Core.NORM_HAMMING, true)
        try {
            orb!!.detectAndCompute(current, mask, keys, descriptors)
            if (descriptors.empty() || referenceDescriptors.empty()) { livePoints = emptyList(); return }
            matcher.match(referenceDescriptors, descriptors, matches)
            val a = referenceKeys.toArray(); val b = keys.toArray()
            val pairs = matches.toArray().filter { it.distance < 58 }.sortedBy { it.distance }.take(160)
            originPoints = pairs.map { a[it.queryIdx].pt }; livePoints = pairs.map { b[it.trainIdx].pt }
        } finally { listOf(keys, descriptors, matches, mask).forEach { it.release() }; matcher.clear() }
    }

    private fun clear() {
        listOf(reference, referenceDescriptors, referenceKeys, previous, cropPoints).forEach { it.release() }
        reference = Mat(); referenceDescriptors = Mat(); referenceKeys = MatOfKeyPoint(); previous = Mat(); cropPoints = MatOfPoint2f()
        originPoints = emptyList(); livePoints = emptyList(); lastMatchMs = 0
        activePlanId = 0L
    }
    override fun close() { clear(); orb?.clear() }
}
