package dev.cameracompositor

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An explicit local research recording. Eight observed views plus relative device rotation.
 * It supplies real phone evidence without claiming translation/depth or unseen-view discovery. */
class ResearchSweepRecorder(
    private val context: Context,
    private val analyzer: SceneFrameAnalyzer,
    private val io: Executor,
    private val zoom: () -> Float,
    private val maximumZoom: () -> Float,
    private val frontCamera: Boolean,
    private val onProgress: (Int) -> Unit,
    private val onFinished: (File?) -> Unit,
) : SensorEventListener {
    private val main = Handler(Looper.getMainLooper())
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var quaternion: FloatArray? = null
    private var active = false
    private var sequence = 0
    private var count = 0
    private var file: File? = null
    private var archive: ZipOutputStream? = null
    private val frames = JSONArray()

    fun start() {
        check(!active)
        active = true
        file = File.createTempFile("composition-sweep-", ".zip", context.cacheDir)
        archive = ZipOutputStream(file!!.outputStream())
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, main) }
        capture()
    }
    private fun capture() {
        if (!active) return
        val request = ++sequence
        val zoomValue = zoom()
        val maxRelativeZoom = (maximumZoom() / zoomValue).coerceIn(1f, 1.8f)
        val rotation = quaternion?.clone()
        val timestamp = SystemClock.elapsedRealtime()
        analyzer.requestSnapshot { frame -> main.post {
            if (!active || request != sequence) { frame?.image?.recycle(); return@post }
            sequence++
            if (frame == null) { cancel(); return@post }
            val index = count
            io.execute {
                val written = runCatching {
                    archive!!.putNextEntry(ZipEntry("frames/${index.toString().padStart(2, '0')}.jpg"))
                    check(frame.image.compress(Bitmap.CompressFormat.JPEG, 92, archive!!))
                    archive!!.closeEntry()
                    val matrix = FloatArray(9); frame.bufferToView.getValues(matrix)
                    frames.put(JSONObject().put("id", index).put("sampleRequestedMs", timestamp).put("zoom", zoomValue)
                        .put("maxRelativeZoom", maxRelativeZoom)
                        .put("width", frame.image.width).put("height", frame.image.height)
                        .put("deviceQuaternionWXYZ", rotation?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                        .put("bufferToView", JSONArray(matrix.toList())))
                }
                frame.image.recycle()
                main.post {
                    if (!active) return@post
                    if (written.isFailure) { cancel(); return@post }
                    count++; onProgress(count)
                    if (count == 8) finish() else main.postDelayed({ capture() }, 450)
                }
            }
        } }
        main.postDelayed({ if (active && sequence == request) cancel() }, 1_500)
    }
    private fun finish() {
        active = false; sequence++; sensors.unregisterListener(this)
        io.execute {
            val result = runCatching {
                val metadata = JSONObject().put("schema", 1).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("androidApi", Build.VERSION.SDK_INT).put("appVersion", BuildConfig.VERSION_NAME)
                    .put("frontCamera", frontCamera).put("frames", frames)
                    .put("notes", "Locally recorded observed views. Rotation is a relative device sensor reading, not camera-pose ground truth. No GPS, key or cloud upload.")
                archive!!.putNextEntry(ZipEntry("observations.json")); archive!!.write(metadata.toString(2).toByteArray())
                archive!!.closeEntry(); archive!!.close(); file!!
            }.getOrNull()
            if (result == null) { runCatching { archive?.close() }; file?.delete() }
            main.post { onFinished(result) }
        }
    }
    fun cancel() {
        if (!active) return
        active = false; sequence++; sensors.unregisterListener(this); analyzer.cancelSnapshot()
        io.execute { runCatching { archive?.close() }; file?.delete(); main.post { onFinished(null) } }
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (active) quaternion = FloatArray(4).also { SensorManager.getQuaternionFromVector(it, event.values) }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
