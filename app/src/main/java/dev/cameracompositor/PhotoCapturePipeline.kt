package dev.cameracompositor

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class SavedPhoto(val uri: Uri, val width: Int, val height: Int, val processing: String)

/** Camera operations stay on main. The gallery publication copies the original on the photo worker. */
class PhotoCapturePipeline(context: Context, private val handler: Handler, private val main: Executor) {
    private val app = context.applicationContext

    fun take(camera: CameraController, base: CameraSelector, extension: Pair<CameraSelector, String>?,
             ownsCamera: () -> Boolean, canShoot: () -> Boolean,
             progress: (String) -> Unit, finished: (Result<SavedPhoto>, Boolean) -> Unit) {
        val zoom = camera.zoomState.value?.zoomRatio ?: 1f
        val capture = Request(camera, base, zoom, ownsCamera, canShoot, progress, finished)
        if (extension == null) capture.shoot(null) else capture.bindExtension(extension)
    }

    private inner class Request(private val camera: CameraController, private val base: CameraSelector,
                                private val zoom: Float,
                                private val ownsCamera: () -> Boolean, private val canShoot: () -> Boolean,
                                private val progress: (String) -> Unit,
                                private val finished: (Result<SavedPhoto>, Boolean) -> Unit) {
        private var needsRestore = false
        private var attempt = 0
        private var complete = false

        fun bindExtension(extension: Pair<CameraSelector, String>) {
            needsRestore = true
            progress("Hold steady for ${extension.second}…")
            try {
                // OEM extensions can reject analysis. The guide has already finished at this point.
                camera.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                camera.cameraSelector = extension.first
                settleZoom { success -> if (success) shoot(extension.second) else fallback() }
            } catch (_: Exception) { fallback() }
        }

        private fun fallback() {
            if (!restore(useZoom = false)) { fail(false); return }
            if (!canShoot()) { fail(); return }
            progress("Capturing with standard camera…")
            settleZoom { success -> if (success) shoot(null) else fail() }
        }

        private fun settleZoom(done: (Boolean) -> Unit) {
            val id = ++attempt
            val applied = runCatching { camera.setZoomRatio(zoom) }.getOrElse { done(false); return }
            val timeout = Runnable { if (!complete && attempt == id) { attempt++; done(false) } }
            handler.postDelayed(timeout, 2200)
            applied.addListener({
                handler.removeCallbacks(timeout)
                if (!complete && attempt == id) {
                    done(canShoot() && runCatching { applied.get() }.isSuccess)
                }
            }, main)
        }

        fun shoot(manufacturer: String?) {
            if (!canShoot()) { fail(); return }
            ++attempt
            var staged: File? = null
            try {
                val file = File.createTempFile("capture-", ".jpg", app.cacheDir)
                staged = file
                val output = ImageCapture.OutputFileOptions.Builder(file).build()
                camera.takePicture(output, main, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        val restored = restore(useZoom = true)
                        complete = true
                        progress("Saving photo…")
                        photoWorker.execute {
                            val photo = runCatching { publish(file, manufacturer) }
                            main.execute { finished(photo, restored) }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        if (manufacturer != null && exception.imageCaptureError != ImageCapture.ERROR_FILE_IO) fallback()
                        else fail()
                    }
                })
            } catch (_: Exception) {
                staged?.delete()
                if (manufacturer != null) fallback() else fail()
            }
        }

        private fun restore(useZoom: Boolean): Boolean {
            if (!needsRestore) return true
            needsRestore = false
            if (!ownsCamera()) return true
            val restored = runCatching {
                camera.cameraSelector = base
                camera.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            }.isSuccess
            if (restored && useZoom) runCatching { camera.setZoomRatio(zoom) }
            return restored
        }

        private fun fail(restored: Boolean = restore(useZoom = true)) {
            if (complete) return
            complete = true
            finished(Result.failure(IllegalStateException("The camera could not finish the photo.")), restored)
        }
    }

    private fun publish(source: File, manufacturer: String?): SavedPhoto {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "The camera returned an unreadable JPEG." }
            val processing = manufacturer ?: "Camera JPEG"
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "CC_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CameraCompositor")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { output -> source.inputStream().use { it.copyTo(output) } }
                val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                check(resolver.update(uri, published, null, null) == 1)
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
            return SavedPhoto(uri, bounds.outWidth, bounds.outHeight, processing)
        } finally { source.delete() }
    }

    companion object { private val photoWorker by lazy { Executors.newSingleThreadExecutor() } }
}
