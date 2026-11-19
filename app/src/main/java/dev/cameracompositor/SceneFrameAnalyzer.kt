package dev.cameracompositor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicReference

data class ScanFrame(val image: Bitmap, val gray: ByteArray, val width: Int, val height: Int,
    val bufferToView: Matrix, val viewSize: Size, val cameraTimestampNs: Long,
    val sensorToBuffer: Matrix?, val scene: LiveScene, val registration: ViewRegistration? = null,
    val attitude: ViewAttitude? = null, val level: CameraLevel? = null)

/** Shared analysis frames feed both the one-shot scan and scene tracking. */
class SceneFrameAnalyzer(private val tracker: SceneTracker, private val motion: MotionOrientation,
                         private val onTrack: (TrackingEvent) -> Unit) : ImageAnalysis.Analyzer {
    val stats = AnalysisStats()
    @Volatile private var sensorToView: Matrix? = null
    @Volatile var viewSize = Size(0, 0)
    private val snapshotRequest = AtomicReference<((ScanFrame?) -> Unit)?>(null)
    private val environment = EnvironmentMotionTracker()
    @Volatile private var environmentActive = false
    val observingEnvironment get() = environmentActive
    @Volatile var trackingGuide = false
    @Volatile private var environmentReset = false
    @Volatile private var environmentTrackedAt = 0L
    @Volatile var liveScene: LiveScene? = null
        private set
    private var checkpointSignature: IntArray? = null
    private var missedEnvironmentFrames = 0
    fun beginEnvironment() { environmentTrackedAt=0; environmentMotion=Float.POSITIVE_INFINITY; environmentReset=true; environmentActive=true }
    fun endEnvironment() { environmentActive=false; environmentReset=true }
    fun environmentFresh(now: Long) = environmentTrackedAt > 0 && now-environmentTrackedAt < 750
    @Volatile private var environmentMotion = Float.POSITIVE_INFINITY
    fun atEnvironmentCheckpoint() = environmentMotion < .04f
    fun closeEnvironment() { environment.close() }
    fun requestSnapshot(callback: (ScanFrame?) -> Unit) { snapshotRequest.getAndSet(callback)?.invoke(null) }
    fun cancelSnapshot() { snapshotRequest.getAndSet(null)?.invoke(null) }
    override fun getTargetCoordinateSystem() = ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED
    override fun getDefaultTargetResolution() = Size(640, 480)
    override fun updateTransform(matrix: Matrix?) { sensorToView = matrix?.let(::Matrix) }

    override fun analyze(image: ImageProxy) {
        val started = SystemClock.elapsedRealtime()
        var failed = false
        try {
            val transform = sensorToView ?: return
            val size = viewSize
            if (size.width == 0 || size.height == 0) return
            val inverse = Matrix()
            if (!image.imageInfo.sensorToBufferTransformMatrix.invert(inverse)) return
            val bufferToView = Matrix().apply { setConcat(transform, inverse) }
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            val base = buffer.position()
            val gray = ByteArray(image.width * image.height)
            for (y in 0 until image.height) {
                if (plane.pixelStride == 1) {
                    buffer.position(base + y * plane.rowStride)
                    buffer.get(gray, y * image.width, image.width)
                } else for (x in 0 until image.width) {
                    gray[y * image.width + x] = buffer.get(base + y * plane.rowStride + x * plane.pixelStride)
                }
            }
            val signature = FrameSignature.sample(gray, image.width, image.height)
            val now = SystemClock.elapsedRealtime()
            val attitude = motion.viewAttitude(size.width, size.height)
            val level = motion.level()
            val scene = LiveScene(now, signature)
            liveScene = scene
            if (environmentReset) {
                environment.close(); checkpointSignature = null; missedEnvironmentFrames = 0; environmentReset=false
            }
            if (environmentActive) {
                // Observation remains useful if feature tracking fails or the subject is textureless.
                val link = runCatching {
                    environment.update(gray,image.width,image.height,bufferToView,size.width,size.height)
                    environment.registration
                }.onFailure { environment.close() }.getOrNull()?.takeIf { it.reliable }
                val sameAngle = checkpointSignature?.let { FrameSignature.sameAngle(it, signature) } == true
                if (sameAngle || link != null) {
                    missedEnvironmentFrames = 0
                    environmentTrackedAt=SystemClock.elapsedRealtime()
                    environmentMotion=if (sameAngle) 0f else link!!.centerMotion
                } else if (++missedEnvironmentFrames >= 3) {
                    environmentTrackedAt = 0
                    environmentMotion = Float.POSITIVE_INFINITY
                }
            }
            snapshotRequest.getAndSet(null)?.let { callback ->
                try {
                    val raw = image.toBitmap()
                    val width = 480
                    val height = (width.toFloat() * size.height / size.width).toInt()
                    val visible = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val scale = Matrix().apply { setScale(width.toFloat() / size.width, height.toFloat() / size.height) }
                    val toBitmap = Matrix().apply { setConcat(scale, bufferToView) }
                    try { Canvas(visible).drawBitmap(raw, toBitmap, null) }
                    catch (error: Exception) { visible.recycle(); throw error }
                    finally { raw.recycle() }
                    val registration = if (environmentActive) {
                        checkpointSignature = signature
                        environmentTrackedAt = SystemClock.elapsedRealtime()
                        environmentMotion = 0f
                        missedEnvironmentFrames = 0
                        runCatching { environment.checkpoint() }.getOrNull()
                    } else null
                    callback(ScanFrame(visible, gray, image.width, image.height, bufferToView, size,
                        image.imageInfo.timestamp, Matrix(image.imageInfo.sensorToBufferTransformMatrix),
                        scene, registration, attitude, level))
                } catch (_: Exception) { callback(null) }
            }
            if (trackingGuide) {
                val tracked = tracker.update(gray, image.width, image.height, bufferToView, size, now, attitude)
                onTrack(TrackingEvent(tracker.activePlanId, tracked))
            }
        } catch (_: Exception) { failed = true; if (trackingGuide) onTrack(TrackingEvent(tracker.activePlanId, null)) }
        finally { stats.record(SystemClock.elapsedRealtime() - started, failed); image.close() }
    }
}
