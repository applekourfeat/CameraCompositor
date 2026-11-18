package dev.cameracompositor

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: GuideOverlay
    private lateinit var guidance: TextView
    private lateinit var telemetry: TextView
    private lateinit var capture: Button
    private lateinit var access: Button
    private lateinit var gallery: Button
    private var controller: LifecycleCameraController? = null
    private var detector: ObjectDetector? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val hold = AlignmentHold()
    private var guideEnabled = true
    private var cameraReady = false
    private var saving = false
    private var generation = 0
    private var lastSubjectMs = 0L
    private var lastTrackingId: Int? = null
    private var lastPhoto: Uri? = null

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) startCamera() else {
            guidance.setText(R.string.camera_permission)
            access.visibility = View.VISIBLE
        }
    }

    private val expireSubject = object : Runnable {
        override fun run() {
            if (overlay.subject != null && SystemClock.elapsedRealtime() - lastSubjectMs > 800) {
                clearSubject()
                if (guideEnabled && cameraReady) guidance.setText(R.string.searching)
            }
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        guidance = findViewById(R.id.guidance)
        telemetry = findViewById(R.id.telemetry)
        capture = findViewById(R.id.capture)
        access = findViewById(R.id.access)
        gallery = findViewById(R.id.gallery)
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        lastPhoto = savedInstanceState?.getString("lastPhoto")?.let(Uri::parse)
        gallery.isEnabled = lastPhoto != null
        access.setOnClickListener { requestCameraAccess() }
        capture.setOnClickListener { takePhoto() }
        gallery.setOnClickListener { openLastPhoto() }
        findViewById<Button>(R.id.flip).setOnClickListener { flipCamera() }
        findViewById<Button>(R.id.assist).setOnClickListener {
            guideEnabled = !guideEnabled
            (it as Button).setText(if (guideEnabled) R.string.assist_on else R.string.assist_off)
            overlay.visibility = if (guideEnabled) View.VISIBLE else View.INVISIBLE
            clearSubject()
            controller?.let { camera ->
                generation++
                camera.clearImageAnalysisAnalyzer()
                detector?.close()
                detector = null
                if (guideEnabled) attachDetector(camera)
                camera.setEnabledUseCases(CameraController.IMAGE_CAPTURE or if (guideEnabled) CameraController.IMAGE_ANALYSIS else 0)
            }
            guidance.setText(if (guideEnabled) R.string.searching else R.string.guide_disabled)
        }
        findViewById<RadioGroup>(R.id.targets).setOnCheckedChangeListener { _, id ->
            overlay.targetX = when (id) {
                R.id.target_left -> 1f / 3f
                R.id.target_center -> .5f
                else -> 2f / 3f
            }
            hold.reset()
            overlay.aligned = false
            overlay.invalidate()
        }
        if (hasCameraPermission()) startCamera() else permissionRequest.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        hold.reset()
        handler.post(expireSubject)
        if (hasCameraPermission() && controller == null) startCamera()
    }

    override fun onPause() {
        handler.removeCallbacks(expireSubject)
        clearSubject()
        super.onPause()
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraAccess() {
        when {
            hasCameraPermission() -> startCamera()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> permissionRequest.launch(Manifest.permission.CAMERA)
            else -> {
                guidance.setText(R.string.camera_settings)
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun startCamera() {
        if (!hasCameraPermission() || controller != null) return
        val camera = LifecycleCameraController(this)
        controller = camera
        camera.imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        camera.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        camera.setEnabledUseCases(CameraController.IMAGE_CAPTURE or if (guideEnabled) CameraController.IMAGE_ANALYSIS else 0)
        if (guideEnabled) attachDetector(camera)
        preview.controller = camera
        try {
            camera.bindToLifecycle(this)
            camera.initializationFuture.addListener({
                if (controller !== camera || isDestroyed) return@addListener
                try {
                    camera.initializationFuture.get()
                    cameraReady = true
                    capture.isEnabled = true
                    access.visibility = View.GONE
                    guidance.setText(if (guideEnabled) R.string.searching else R.string.guide_disabled)
                } catch (_: Exception) { cameraFailed(camera) }
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) { cameraFailed(camera) }
    }

    private fun cameraFailed(camera: LifecycleCameraController) {
        generation++
        camera.clearImageAnalysisAnalyzer()
        detector?.close()
        detector = null
        camera.unbind()
        if (controller === camera) controller = null
        cameraReady = false
        capture.isEnabled = false
        guidance.setText(R.string.camera_error)
        access.visibility = View.VISIBLE
    }

    private fun attachDetector(camera: LifecycleCameraController) {
        val currentGeneration = ++generation
        val subjectDetector = ObjectDetection.getClient(ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE).build())
        detector = subjectDetector
        val mlAnalyzer = MlKitAnalyzer(listOf(subjectDetector), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            ContextCompat.getMainExecutor(this)) { result ->
            if (isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
                !guideEnabled || currentGeneration != generation) return@MlKitAnalyzer
            val error = result.getThrowable(subjectDetector)
            if (error != null) {
                clearSubject()
                guidance.setText(if ((error as? MlKitException)?.errorCode == MlKitException.UNAVAILABLE)
                    R.string.model_wait else R.string.detector_error)
                return@MlKitAnalyzer
            }
            val found = result.getValue(subjectDetector)?.firstOrNull()
            if (found == null || preview.width == 0 || preview.height == 0) {
                clearSubject()
                guidance.setText(R.string.searching)
                return@MlKitAnalyzer
            }
            val bounds = found.boundingBox
            val raw = SubjectBox(bounds.left.toFloat() / preview.width, bounds.top.toFloat() / preview.height,
                bounds.right.toFloat() / preview.width, bounds.bottom.toFloat() / preview.height)
            val previous = overlay.subject
            val box = if (previous != null && found.trackingId != null && found.trackingId == lastTrackingId) {
                fun blend(old: Float, new: Float) = old * .55f + new * .45f
                SubjectBox(blend(previous.left, raw.left), blend(previous.top, raw.top),
                    blend(previous.right, raw.right), blend(previous.bottom, raw.bottom))
            } else { hold.reset(); raw }
            lastTrackingId = found.trackingId
            lastSubjectMs = SystemClock.elapsedRealtime()
            val cue = CompositionEngine.evaluate(box, overlay.targetX)
            val aligned = hold.update(cue, lastSubjectMs)
            overlay.subject = box
            overlay.aligned = aligned
            overlay.invalidate()
            guidance.setText(when (cue) {
                Cue.SEARCHING -> R.string.searching
                Cue.SPACE -> R.string.step_back
                Cue.LEFT -> R.string.pan_left
                Cue.RIGHT -> R.string.pan_right
                Cue.UP -> R.string.pan_up
                Cue.DOWN -> R.string.pan_down
                Cue.ALIGNED -> if (aligned) R.string.aligned else R.string.settling
            })
            telemetry.setText(R.string.tracking_telemetry)
        }
        camera.setImageAnalysisAnalyzer(analysisExecutor, ThrottledAnalyzer(mlAnalyzer))
    }

    private fun clearSubject() {
        overlay.subject = null
        overlay.aligned = false
        lastTrackingId = null
        hold.reset()
        overlay.invalidate()
    }

    private fun flipCamera() {
        val camera = controller ?: return
        if (!cameraReady || saving) return
        val previous = camera.cameraSelector
        val next = if (previous == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!camera.hasCamera(next)) { toast(R.string.no_other_camera); return }
        try {
            generation++
            camera.clearImageAnalysisAnalyzer()
            detector?.close()
            detector = null
            clearSubject()
            camera.cameraSelector = next
            if (guideEnabled) attachDetector(camera)
        } catch (_: Exception) {
            camera.cameraSelector = previous
            if (guideEnabled) attachDetector(camera)
            toast(R.string.no_other_camera)
        }
    }

    private fun takePhoto() {
        val camera = controller ?: return
        if (!cameraReady || saving) return
        saving = true
        capture.isEnabled = false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "CC_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CameraCompositor")
        }
        val output = ImageCapture.OutputFileOptions.Builder(contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        try {
            camera.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    saving = false
                    if (isDestroyed) return
                    capture.isEnabled = cameraReady
                    lastPhoto = result.savedUri
                    gallery.isEnabled = lastPhoto != null
                    toast(R.string.photo_saved)
                }
                override fun onError(exception: ImageCaptureException) { saveFailed() }
            })
        } catch (_: Exception) { saveFailed() }
    }

    private fun saveFailed() {
        saving = false
        if (isDestroyed) return
        capture.isEnabled = cameraReady
        toast(R.string.photo_failed)
    }

    private fun openLastPhoto() {
        val uri = lastPhoto ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: android.content.ActivityNotFoundException) { toast(R.string.no_gallery) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("lastPhoto", lastPhoto?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        controller?.clearImageAnalysisAnalyzer()
        controller?.unbind()
        detector?.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
