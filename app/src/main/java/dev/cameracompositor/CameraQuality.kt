package dev.cameracompositor

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

/** Vendor processing is queried on this phone, rather than inferred from its model name. */
class CameraQuality(context: Context, private val onReady: () -> Unit) {
    private var extensions: ExtensionsManager? = null
    var status = "Checking manufacturer processing…"
        private set
    init {
        val main = ContextCompat.getMainExecutor(context)
        val provider = ProcessCameraProvider.getInstance(context)
        provider.addListener({
            try {
                val future = ExtensionsManager.getInstanceAsync(context, provider.get())
                future.addListener({
                    try { extensions = future.get(); status = "Manufacturer processing checked" }
                    catch (_: Exception) { status = "Manufacturer processing unavailable" }
                    onReady()
                }, main)
            } catch (_: Exception) { status = "Manufacturer processing unavailable"; onReady() }
        }, main)
    }

    fun supported(base: CameraSelector): List<Pair<Int, String>> = listOf(ExtensionMode.AUTO to "Auto",
        ExtensionMode.HDR to "HDR", ExtensionMode.NIGHT to "Night").filter {
        runCatching { extensions?.isExtensionAvailable(base, it.first) == true }.getOrDefault(false)
    }

    fun selector(base: CameraSelector, mode: Int): CameraSelector? = runCatching {
        extensions?.takeIf { it.isExtensionAvailable(base, mode) }?.getExtensionEnabledCameraSelector(base, mode)
    }.getOrNull()

    fun processing(base: CameraSelector, mode: PhotoMode, light: PhotoLighting): Pair<CameraSelector, String>? {
        val requested = if (mode != PhotoMode.ADAPTIVE) listOf(mode) else when (light) {
            PhotoLighting.ORDINARY -> return null
            PhotoLighting.DIM -> listOf(PhotoMode.NIGHT, PhotoMode.AUTO)
            PhotoLighting.BACKLIT -> listOf(PhotoMode.HDR, PhotoMode.AUTO)
        }
        for (kind in requested) {
            val (extension, name) = when (kind) {
                PhotoMode.AUTO -> ExtensionMode.AUTO to "Manufacturer Auto"
                PhotoMode.HDR -> ExtensionMode.HDR to "Manufacturer HDR"
                PhotoMode.NIGHT -> ExtensionMode.NIGHT to "Manufacturer Night"
                else -> continue
            }
            selector(base, extension)?.let { return it to name }
        }
        return null
    }
}
