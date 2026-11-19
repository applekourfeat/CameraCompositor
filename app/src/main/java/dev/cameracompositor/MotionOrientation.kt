package dev.cameracompositor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

/** Optional rear-camera rotation input. Sensor callbacks publish an immutable sample to the analyzer. */
class MotionOrientation(context: Context) : SensorEventListener {
    private data class Sample(val matrix: FloatArray, val timestampNs: Long)
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    @Volatile private var sample: Sample? = null
    @Volatile var focalPerViewWidth: Float? = null
    @Volatile var zoom = 1f
    @Volatile var displayRotation = Surface.ROTATION_0

    fun start() { sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { manager?.unregisterListener(this); sample = null }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        if (rotation.all(Float::isFinite)) sample = Sample(rotation, event.timestamp)
    }

    fun viewAttitude(width: Int, height: Int): ViewAttitude? {
        val input = sample ?: return null
        val focal = focalPerViewWidth ?: return null
        if (width <= 0 || height <= 0 || SystemClock.elapsedRealtimeNanos() - input.timestampNs !in 0L..200_000_000L) return null
        val normalized = when (displayRotation) {
            Surface.ROTATION_0 -> input.matrix
            else -> FloatArray(9).also { output ->
                val axes = when (displayRotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> return null
                }
                if (!SensorManager.remapCoordinateSystem(input.matrix, axes.first, axes.second, output)) return null
            }
        }
        val fx = focal * zoom
        return ViewAttitude(normalized, input.timestampNs, fx, fx * width / height)
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun configure(info: CameraInfo?, rear: Boolean) {
        focalPerViewWidth = if (rear && info != null) runCatching {
            val camera = Camera2CameraInfo.from(info)
            val sizes = camera.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching null
            val focal = camera.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: return@runCatching null
            (focal / minOf(sizes.width, sizes.height)).takeIf { it.isFinite() && it in .45f..2.2f }
        }.getOrNull() else null
    }
}
