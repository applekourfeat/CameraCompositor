package dev.cameracompositor

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: GuideOverlay
    private lateinit var guidance: TextView
    private lateinit var telemetry: TextView
    private lateinit var capture: Button
    private lateinit var access: Button
    private lateinit var gallery: Button
    private lateinit var find: Button
    private lateinit var cancel: Button
    private lateinit var flip: Button
    private lateinit var plannerSettings: PlannerSettings
    private lateinit var planner: CloudShotPlanner
    private lateinit var quality: CameraQuality
    private val tracker = SceneTracker()
    private lateinit var analyzer: SceneFrameAnalyzer
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val main by lazy { ContextCompat.getMainExecutor(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val hold = AlignmentHold()
    private var controller: LifecycleCameraController? = null
    private var baseSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraReady = false
    private var saving = false
    private var scanning = false
    private var activePlan = false
    private var activePlanId = 0L
    @Volatile private var generation = 0
    private var autoZoom = true
    private var zoomBusy = false
    private var baselineZoom = 1f
    private var lastGood: TrackedFrame? = null
    private var lastPhoto: Uri? = null
    private var modelReason = ""
    private var lastScanMs = 0L
    private var lastPhotoDetails = "No photo taken in this session"
    private var processingMode: Int? = null // null = prefer manufacturer Auto/HDR; NONE = standard

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) startCamera() else { guidance.setText(R.string.camera_permission); access.visibility = View.VISIBLE }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (activePlan && !saving && lastGood?.let { SystemClock.elapsedRealtime() - it.timeMs > 350 } != false) trackingLost()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        preview = findViewById(R.id.preview); overlay = findViewById(R.id.overlay)
        guidance = findViewById(R.id.guidance); telemetry = findViewById(R.id.telemetry)
        capture = findViewById(R.id.capture); access = findViewById(R.id.access); gallery = findViewById(R.id.gallery)
        find = findViewById(R.id.find_shot); cancel = findViewById(R.id.cancel); flip = findViewById(R.id.flip)
        plannerSettings = PlannerSettings(this); planner = CloudShotPlanner(plannerSettings)
        analyzer = SceneFrameAnalyzer(tracker) { event -> handler.post {
            if (!isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activePlan && !saving && event.planId == activePlanId) {
                if (event.frame == null) trackingLost() else showTrack(event.frame)
            }
        } }
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> analyzer.viewSize = Size(preview.width, preview.height) }
        preview.setOnTouchListener { _, event ->
            if (event.pointerCount > 1 && activePlan) { autoZoom = false; telemetry.text = "Zoom is yours. Cancel guide to reset." }
            false
        }
        quality = CameraQuality(this) { if (!isDestroyed) updateCameraStatus() }
        lastPhoto = savedInstanceState?.getString("lastPhoto")?.let(Uri::parse)
        gallery.isEnabled = lastPhoto != null
        access.setOnClickListener { requestCameraAccess() }
        capture.setOnClickListener { takePhoto() }; gallery.setOnClickListener { openLastPhoto() }
        find.setOnClickListener { requestComposition() }
        cancel.setOnClickListener { cancelGuide(true) }
        flip.setOnClickListener { flipCamera() }
        findViewById<Button>(R.id.settings).setOnClickListener { showOptions() }
        if (hasPermission()) startCamera() else permissionRequest.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume(); handler.post(tick)
        if (::analyzer.isInitialized && hasPermission() && controller == null) startCamera()
    }
    override fun onPause() {
        handler.removeCallbacks(tick)
        if (::analyzer.isInitialized) cancelGuide(false)
        if (::planner.isInitialized) { planner.cancel(); modelExecutor.execute { planner.close() } }
        super.onPause()
    }
    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun requestCameraAccess() {
        when {
            hasPermission() -> startCamera()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> permissionRequest.launch(Manifest.permission.CAMERA)
            else -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun startCamera() {
        if (!hasPermission() || controller != null) return
        val camera = LifecycleCameraController(this)
        controller = camera
        try {
            camera.imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            camera.imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
            camera.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            camera.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            camera.setImageAnalysisAnalyzer(analysisExecutor, ThrottledAnalyzer(analyzer))
            preview.controller = camera; camera.cameraSelector = baseSelector; camera.bindToLifecycle(this)
            camera.initializationFuture.addListener({
                if (controller !== camera || isDestroyed) return@addListener
                try {
                    camera.initializationFuture.get(); cameraReady = true; access.visibility = View.GONE
                    guidance.text = "Point at a scene, then find a composition."
                    refreshButtons(); updateCameraStatus()
                } catch (_: Exception) { cameraFailed(camera) }
            }, main)
        } catch (_: Exception) { cameraFailed(camera) }
    }
    private fun cameraFailed(camera: LifecycleCameraController) {
        camera.clearImageAnalysisAnalyzer(); camera.unbind(); if (controller === camera) controller = null
        cameraReady = false; refreshButtons(); guidance.setText(R.string.camera_error); access.visibility = View.VISIBLE
    }

    private fun scan(manual: Boolean) {
        if (!cameraReady || scanning || saving) return
        cancelGuide(false)
        val token = ++generation
        val cloudRequest = planner.beginRequest()
        scanning = true; baselineZoom = controller?.zoomState?.value?.zoomRatio ?: 1f
        val maxRelativeZoom = ((controller?.zoomState?.value?.maxZoomRatio ?: baselineZoom) / baselineZoom).coerceIn(1f, 1.8f)
        guidance.text = "Hold this view while the shot is selected…"; refreshButtons()
        handler.postDelayed({ if (token == generation && scanning) { cancelGuide(false); guidance.text = "No camera frame arrived. Try again." } }, 5000)
        analyzer.requestSnapshot { frame -> handler.post {
            if (token != generation || isDestroyed) { frame?.image?.recycle(); return@post }
            if (frame == null) { scanning = false; refreshButtons(); guidance.text = "Could not scan this frame. Try again."; return@post }
            // The frame timeout applies only to acquisition, not to model inference.
            val inferenceToken = ++generation
            if (manual) { showManualChoices(frame, inferenceToken); return@post }
            val started = SystemClock.elapsedRealtime()
            modelExecutor.execute {
                if (inferenceToken != generation) { frame.image.recycle(); return@execute }
                val result = runCatching { planner.choose(frame.image, cloudRequest, maxRelativeZoom) { stage -> handler.post {
                    if (inferenceToken == generation && !isDestroyed) guidance.text = stage
                } } }
                handler.post {
                    if (inferenceToken != generation || isDestroyed) { frame.image.recycle(); return@post }
                    scanning = false; lastScanMs = SystemClock.elapsedRealtime() - started
                    result.onSuccess { applyChoice(frame, it) }.onFailure { error ->
                        guidance.text = "Scene scan failed. Camera still works."; refreshButtons()
                        AlertDialog.Builder(this).setTitle("Scene scan could not finish")
                            .setMessage(error.message?.take(350) ?: "Unknown model error")
                            .setPositiveButton("Choose a framing yourself") { _, _ -> showManualChoices(frame, generation) }
                            .setNegativeButton("Close") { _, _ -> frame.image.recycle() }.setOnCancelListener { frame.image.recycle() }.show()
                    }
                }
            }
        } }
    }

    private fun showManualChoices(frame: ScanFrame, token: Int) {
        val sheet = CropPreviews.contactSheet(frame.image)
        val image = ImageView(this).apply { setImageBitmap(sheet); adjustViewBounds = true }
        val picker = NumberPicker(this).apply { minValue = 0; maxValue = 8; wrapSelectorWheel = false }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(resources.displayMetrics.heightPixels * .4f, 320 * resources.displayMetrics.density).toInt()))
            addView(picker)
        }
        AlertDialog.Builder(this).setTitle("Choose a framing (0 to 8)")
            .setView(panel)
            .setPositiveButton("Use frame") { _, _ ->
                scanning = false
                if (token == generation) applyChoice(frame, ModelChoice(picker.value, "Your selected framing")) else frame.image.recycle()
                sheet.recycle(); refreshButtons()
            }.setNegativeButton("Cancel") { _, _ -> scanning = false; frame.image.recycle(); sheet.recycle(); refreshButtons() }
            .setOnCancelListener { scanning = false; frame.image.recycle(); sheet.recycle(); refreshButtons() }.show()
    }

    private fun applyChoice(frame: ScanFrame, choice: ModelChoice) {
        val crop = choice.customCrop?.let { CropOption(-1, it) } ?: ShotGeometry.options[choice.cropId]
        val b = crop.box
        overlay.targetPreview?.recycle()
        val selected = Bitmap.createBitmap(frame.image, (b.left * frame.image.width).toInt(),
            (b.top * frame.image.height).toInt(), ((b.right - b.left) * frame.image.width).toInt(),
            ((b.bottom - b.top) * frame.image.height).toInt())
        overlay.targetPreview = Bitmap.createScaledBitmap(selected, 180, 240, true)
        if (selected !== frame.image && selected !== overlay.targetPreview) selected.recycle()
        activePlanId = tracker.select(frame, crop)
        modelReason = choice.explanation; activePlan = true; autoZoom = true; lastGood = null
        guidance.text = "Move toward the outlined frame."
        telemetry.text = choice.explanation
        frame.image.recycle(); refreshButtons()
    }

    private fun showTrack(tracked: TrackedFrame) {
        if (SystemClock.elapsedRealtime() - tracked.timeMs > 350) { trackingLost(); return }
        lastGood = tracked; overlay.frame = tracked.corners; overlay.fresh = true
        val aligned = hold.update(ShotGeometry.framingReady(tracked.corners), tracked.timeMs)
        if (aligned && !overlay.aligned) overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        overlay.aligned = aligned; overlay.invalidate()
        guidance.text = when {
            aligned -> "That's the frame. Hold steady and take the photo."
            ShotGeometry.aimReady(tracked.corners) && !autoZoom -> "Pinch to match the outlined frame."
            ShotGeometry.aimReady(tracked.corners) && autoZoom -> "Framing your shot…"
            else -> "Move toward the outlined frame."
        }
        if (autoZoom && !zoomBusy) {
            val camera = controller ?: return
            val zoom = camera.zoomState.value ?: return
            val desired = ShotGeometry.desiredZoom(zoom.zoomRatio, tracked.corners, baselineZoom, zoom.maxZoomRatio) ?: return
            if (abs(desired - zoom.zoomRatio) > .025f) {
                val next = zoom.zoomRatio + (desired - zoom.zoomRatio).coerceIn(-.04f, .04f)
                zoomBusy = true
                val future = camera.setZoomRatio(next)
                future.addListener({ zoomBusy = false; runCatching { future.get() }.onFailure { autoZoom = false } }, main)
            }
        }
    }
    private fun trackingLost() {
        hold.reset(); overlay.aligned = false; overlay.fresh = false
        val age = lastGood?.let { SystemClock.elapsedRealtime() - it.timeMs } ?: Long.MAX_VALUE
        if (age > 600) overlay.frame = null
        overlay.invalidate()
        guidance.text = if (age < 600) "Holding your shot plan…" else "Return to the scanned scene, or find another shot."
    }
    private fun cancelGuide(resetZoom: Boolean) {
        generation++; planner.cancel(); analyzer.cancelSnapshot(); tracker.reset()
        scanning = false; activePlan = false; lastGood = null; hold.reset()
        overlay.frame = null; overlay.aligned = false; overlay.fresh = false
        overlay.targetPreview?.recycle(); overlay.targetPreview = null; overlay.invalidate()
        if (resetZoom) { controller?.setZoomRatio(baselineZoom); guidance.text = "Point at a scene, then find a composition." }
        refreshButtons()
    }

    private fun requestComposition() {
        if (plannerSettings.key() == null) { showAiSetup(); return }
        if (plannerSettings.consent) { scan(false); return }
        AlertDialog.Builder(this).setTitle("Send one scene image to Google?")
            .setMessage("Each tap of Find a composition sends one reduced camera image to Google's Gemini API. " +
                "Live tracking and your full-resolution saved photo stay on the phone. " +
                "Google's free tier may use submitted images to improve its products. " +
                "Your API project's pricing and quota apply. This test build limits scans to 20 attempts per day.")
            .setPositiveButton("Allow scene scans") { _, _ -> plannerSettings.consentToUpload(); scan(false) }
            .setNeutralButton("Privacy and pricing") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://ai.google.dev/gemini-api/docs/pricing"))) }
            .setNegativeButton("Keep camera local", null).show()
    }

    private fun showAiSetup() {
        cancelGuide(false)
        val keyInput = EditText(this).apply {
            hint = if (plannerSettings.key() == null) "Paste your personal API key" else "Saved key retained if you leave this blank"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setSingleLine(true)
        }
        val modelInput = EditText(this).apply { setText(plannerSettings.model); hint = "Gemini model ID"; setSingleLine(true) }
        val accessStatus = TextView(this)
        val checkAccess = Button(this).apply {
            text = "Check key and model"; isAllCaps = false
            setOnClickListener {
                val credential = keyInput.text.toString().trim().ifBlank { plannerSettings.key().orEmpty() }
                val model = modelInput.text.toString().trim()
                isEnabled = false; accessStatus.text = "Checking access without uploading an image…"
                modelExecutor.execute {
                    val result = runCatching { CloudShotPlanner.checkAccess(credential, model) }
                    handler.post { if (!isDestroyed) {
                        isEnabled = true; accessStatus.text = result.getOrElse { it.message ?: "Access check failed." }
                    } }
                }
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8)
            addView(TextView(this@MainActivity).apply { text = "Personal test setup. The key is encrypted on this phone. " +
                "Use your own key; keep it out of chat and GitHub. Your Google project's pricing and quota apply." })
            addView(keyInput); addView(modelInput); addView(checkAccess); addView(accessStatus)
        }
        val dialog = AlertDialog.Builder(this).setTitle("AI setup").setView(panel)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .setNeutralButton("Open AI Studio") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/api-keys"))) }.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { keyInput.text.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { plannerSettings.save(keyInput.text.toString().ifBlank { plannerSettings.key().orEmpty() }, modelInput.text.toString().trim()) }
                    .onSuccess { keyInput.text.clear(); dialog.dismiss(); guidance.text = "AI setup saved. Tap Find a composition." }
                    .onFailure { keyInput.error = it.message }
            }
        }
        dialog.show()
    }
    private fun showOptions() {
        AlertDialog.Builder(this).setTitle("Camera options").setItems(arrayOf("AI setup", "Try the framing guide without AI",
            "Photo processing", "Camera and scan details", "Google privacy and pricing", "Forget saved API key")) { _, index ->
            when (index) {
                0 -> showAiSetup()
                1 -> scan(true)
                2 -> chooseProcessing()
                3 -> AlertDialog.Builder(this).setTitle("Device details").setMessage(
                    "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}\n${quality.status}\n" +
                    "Exposed extensions: ${quality.supported(baseSelector).joinToString { it.second }.ifBlank { "none" }}\n" +
                    "Capture: quality priority, standard high-resolution 4:3 JPEG\n$lastPhotoDetails\n" +
                    "Model backend: ${planner.backendLabel.ifBlank { "not loaded" }}\nLast scan: ${lastScanMs / 1000f} s\n$modelReason")
                    .setPositiveButton("Close", null).show()
                4 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ai.google.dev/gemini-api/docs/pricing")))
                5 -> { plannerSettings.forget(); guidance.text = "Saved API key removed." }
            }
        }.show()
    }
    private fun chooseProcessing() {
        val modes = listOf(null to "Automatic manufacturer processing", ExtensionMode.NONE to "Standard quality capture") + quality.supported(baseSelector)
        AlertDialog.Builder(this).setTitle("Photo processing").setItems(modes.map { it.second }.toTypedArray()) { _, index ->
            processingMode = modes[index].first; updateCameraStatus()
        }.show()
    }
    private fun updateCameraStatus() {
        if (!::telemetry.isInitialized || activePlan || scanning) return
        val extension = captureExtension()
        telemetry.text = "${extension?.second ?: "Standard quality"} · 4:3 · tap to focus · pinch to zoom"
    }
    private fun captureExtension(): Pair<CameraSelector, String>? = when (val mode = processingMode) {
        null -> quality.automatic(baseSelector)
        ExtensionMode.NONE -> null
        else -> quality.selector(baseSelector, mode)?.let { it to "Manufacturer ${quality.supported(baseSelector).firstOrNull { entry -> entry.first == mode }?.second}" }
    }

    private fun flipCamera() {
        val camera = controller ?: return
        if (!cameraReady || saving || scanning) return
        val next = if (baseSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!camera.hasCamera(next)) { toast(R.string.no_other_camera); return }
        cancelGuide(false)
        val previous = baseSelector
        try { camera.cameraSelector = next; baseSelector = next; updateCameraStatus() }
        catch (_: Exception) { camera.cameraSelector = previous; toast(R.string.no_other_camera) }
    }

    private fun takePhoto() {
        val camera = controller ?: return
        if (!cameraReady || saving || scanning) return
        saving = true; refreshButtons()
        val zoom = camera.zoomState.value?.zoomRatio ?: 1f
        val extension = captureExtension()
        var used = extension?.second ?: "Standard quality"
        var zoomRequest: com.google.common.util.concurrent.ListenableFuture<Void>? = null
        if (extension != null) {
            try {
                // Extensions cannot reliably run ImageAnalysis. Switch only for the still capture.
                camera.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                camera.cameraSelector = extension.first; zoomRequest = camera.setZoomRatio(zoom)
                guidance.text = "Capturing with ${extension.second}…"
            } catch (_: Exception) {
                camera.cameraSelector = baseSelector
                camera.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
                used = "Standard quality (manufacturer processing failed)"
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "CC_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CameraCompositor")
        }
        val output = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        val usedProcessing = used
        fun restore() {
            if (!isDestroyed) {
                runCatching { camera.cameraSelector = baseSelector; camera.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS); camera.setZoomRatio(zoom) }
                    .onFailure { cameraFailed(camera) }
                saving = false; hold.reset(); refreshButtons()
            }
        }
        fun performCapture() { try {
            camera.takePicture(output, main, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    restore(); if (isDestroyed) return
                    lastPhoto = result.savedUri; gallery.isEnabled = lastPhoto != null
                    toast(R.string.photo_saved)
                    modelExecutor.execute {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        runCatching { result.savedUri?.let { uri -> contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) } } }
                        handler.post { if (!isDestroyed) {
                            lastPhotoDetails = "Last photo: ${bounds.outWidth} × ${bounds.outHeight} · $usedProcessing"
                            telemetry.text = lastPhotoDetails
                        } }
                    }
                }
                override fun onError(exception: ImageCaptureException) { restore(); toast(R.string.photo_failed) }
            })
        } catch (_: Exception) { restore(); toast(R.string.photo_failed) } }
        val zoomFuture = zoomRequest
        if (zoomFuture == null) performCapture() else {
            var settled = false
            zoomFuture.addListener({
                if (!settled) {
                    settled = true
                    runCatching { zoomFuture.get() }.onSuccess { if (!isDestroyed) performCapture() }
                        .onFailure { restore(); toast(R.string.photo_failed) }
                }
            }, main)
            handler.postDelayed({
                if (!settled && !isDestroyed) {
                    settled = true; restore()
                    guidance.text = "Camera zoom did not settle. Try Standard quality in Photo processing."
                }
            }, 3500)
        }
    }
    private fun openLastPhoto() {
        lastPhoto?.let { uri -> runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { toast(R.string.no_gallery) } }
    }
    private fun refreshButtons() {
        if (!::find.isInitialized) return
        capture.isEnabled = cameraReady && !saving && !scanning
        find.isEnabled = cameraReady && !saving && !scanning
        flip.isEnabled = cameraReady && !saving && !scanning
        cancel.visibility = if (activePlan || scanning) View.VISIBLE else View.GONE
    }
    private fun toast(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_SHORT).show()
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("lastPhoto", lastPhoto?.toString()); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        generation++; handler.removeCallbacksAndMessages(null); analyzer.cancelSnapshot()
        controller?.clearImageAnalysisAnalyzer(); controller?.unbind(); planner.cancel()
        analysisExecutor.execute { tracker.close() }; analysisExecutor.shutdown()
        modelExecutor.execute { planner.close() }; modelExecutor.shutdown()
        super.onDestroy()
    }
}
