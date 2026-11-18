package dev.cameracompositor

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.os.Build

/** Runtime inventory only. Hardware marketing specifications do not establish app access. */
object CameraCapabilityReport {
    fun collect(context: Context): String = buildString {
        appendLine("Camera Compositor ${BuildConfig.VERSION_NAME}")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("CameraX 1.6.2; capabilities exposed to this app:")
        val manager = context.getSystemService(CameraManager::class.java)
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            appendLine("\nCamera $id; facing=${c.get(C.LENS_FACING)}; hardware=${c.get(C.INFO_SUPPORTED_HARDWARE_LEVEL)}")
            appendLine("Focal lengths (mm): ${c.get(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()}")
            appendLine("Physical camera IDs: ${c.physicalCameraIds.sorted().joinToString().ifBlank { "none exposed" }}")
            appendLine("Maximum digital zoom: ${c.get(C.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)}")
            appendLine("OIS modes: ${c.get(C.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.joinToString()}")
            val capabilities = c.get(C.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            appendLine("RAW: ${C.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities}")
            appendLine("Depth output capability: ${C.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in capabilities}")
            appendLine("Intrinsic calibration fx,fy,cx,cy,skew: ${c.get(C.LENS_INTRINSIC_CALIBRATION)?.joinToString() ?: "not exposed"}")
            appendLine("Lens distortion: ${c.get(C.LENS_DISTORTION)?.joinToString() ?: "not exposed"}")
            appendLine("Sensor active array: ${c.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)}")
            appendLine("Pre-correction active array: ${c.get(C.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)}")
            appendLine("Sensor physical size: ${c.get(C.SENSOR_INFO_PHYSICAL_SIZE)}; orientation=${c.get(C.SENSOR_ORIENTATION)}")
            appendLine("Sensor timestamp source: ${c.get(C.SENSOR_INFO_TIMESTAMP_SOURCE)}")
            val map = c.get(C.SCALER_STREAM_CONFIGURATION_MAP)
            val jpeg = map?.getOutputSizes(ImageFormat.JPEG).orEmpty().sortedByDescending { it.width.toLong() * it.height }
            appendLine("JPEG sizes: ${jpeg.take(12).joinToString { "${it.width}x${it.height}" }}")
            appendLine("Depth16 sizes: ${map?.getOutputSizes(ImageFormat.DEPTH16)?.joinToString { "${it.width}x${it.height}" } ?: "none exposed"}")
            if (Build.VERSION.SDK_INT >= 34) appendLine("Ultra HDR JPEG/R exposed: ${map?.outputFormats?.contains(ImageFormat.JPEG_R) == true}")
        }
        appendLine("\nThis inventory does not establish ARCore support or aligned, per-frame camera calibration.")
        appendLine("Image quality, geometry and thermal performance require photographs and measurements on this phone.")
    }
}
