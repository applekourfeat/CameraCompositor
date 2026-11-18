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
    private lateinit var localScorer: LocalResearchScorer
    private lateinit var softwareDepth: SoftwareDepth
    private lateinit var localComposition: LocalComposition
    private lateinit var quality: CameraQuality
    private var sweepRecorder: ResearchSweepRecorder? = null
    private var environmentSweep: EnvironmentSweep? = null
    private var environmentDialog: AlertDialog? = null
    private var activeEnvironmentBatch: EnvironmentBatch? = null
    private val environmentRoute=ArrayDeque<Pair<ScanFrame,ModelChoice>>()
    private var planZoomLimit = 1.8f
    private var pendingSweep: java.io.File? = null
    private val exportSweep = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingSweep
        if (uri != null && file != null) modelExecutor.execute {
            val success = runCatching {
                contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("Export destination unavailable")
            }.isSuccess
            handler.post { if (!isDestroyed) guidance.text = if (success) "Research sweep exported." else "The research sweep could not be exported." }
        }
    }
    private val tracker = SceneTracker()
    private lateinit var analyzer: SceneFrameAnalyzer
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val main by lazy { ContextCompat.getMainExecutor(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val hold = AlignmentHold()
    private val guideLifetime = GuideLifetime()
    @Volatile private var lastDiagnostic = "No scene request recorded in this build."
    private var controller: LifecycleCameraController? = null
    private var baseSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val interaction = CameraInteractionState { renderControls(it) }
    private var cameraReady: Boolean
        get() = interaction.ready
        set(value) { interaction.ready = value }
    private var saving: Boolean
        get() = interaction.saving
        set(value) { interaction.saving = value }
    private var scanning: Boolean
        get() = interaction.scanning
        set(value) { interaction.scanning = value }
    private var activePlan: Boolean
        get() = interaction.guiding
        set(value) { interaction.guiding = value }
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
        plannerSettings = PlannerSettings(this)
        localScorer = LocalResearchScorer(this)
        softwareDepth = SoftwareDepth(this)
        localComposition = LocalComposition(softwareDepth)
        lastDiagnostic = runCatching { java.io.File(filesDir, "last-scan-diagnostic.json").readText() }.getOrDefault(lastDiagnostic)
        planner = CloudShotPlanner(plannerSettings) { report ->
            lastDiagnostic = report
            runCatching { java.io.File(filesDir, "last-scan-diagnostic.json").writeText(report) }
        }
        analyzer = SceneFrameAnalyzer(tracker) { event -> handler.post {
            if (!isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activePlan && !saving && event.planId == activePlanId) {
                if (event.frame == null) trackingLost() else showTrack(event.frame)
            }
        } }
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> analyzer.viewSize = Size(preview.width, preview.height) }
        preview.setOnTouchListener { _, event ->
            if (event.pointerCount > 1 && scanning) cancelGuide(false)
            if (event.pointerCount > 1 && activeEnvironmentBatch!=null) {
                cancelGuide(false); guidance.text="Zoom is yours. Find a composition when ready."
            }
            if (event.pointerCount > 1 && activePlan) { autoZoom = false; telemetry.text = "Zoom is yours. Cancel guide to reset." }
            false
        }
        quality = CameraQuality(this) { if (!isDestroyed) updateCameraStatus() }
        lastPhoto = savedInstanceState?.getString("lastPhoto")?.let(Uri::parse)
        gallery.isEnabled = lastPhoto != null
        access.setOnClickListener { requestCameraAccess() }
        capture.setOnClickListener { takePhoto() }; gallery.setOnClickListener { openLastPhoto() }
        find.setOnClickListener {
            scanEnvironment()
        }
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
            camera.imageAnalysisResolutionSelector=ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(640,480),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
            camera.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            camera.setImageAnalysisAnalyzer(analysisExecutor, ThrottledAnalyzer(analyzer) { if (analyzer.observingEnvironment) 33L else 100L })
            preview.controller = camera; camera.cameraSelector = baseSelector; camera.bindToLifecycle(this)
            camera.initializationFuture.addListener({
                if (controller !== camera || isDestroyed) return@addListener
                try {
                    camera.initializationFuture.get(); cameraReady = true; access.visibility = View.GONE
                    guidance.setText(R.string.ready_to_frame)
                    refreshButtons(); updateCameraStatus()
                } catch (_: Exception) { cameraFailed(camera) }
            }, main)
        } catch (_: Exception) { cameraFailed(camera) }
    }
    private fun cameraFailed(camera: LifecycleCameraController) {
        camera.clearImageAnalysisAnalyzer(); camera.unbind(); if (controller === camera) controller = null
        cameraReady = false; refreshButtons(); guidance.setText(R.string.camera_error); access.visibility = View.VISIBLE
    }

    private fun requestOnlineEnvironmentScan() {
        if (!cameraReady || scanning || saving) return
        if (plannerSettings.key()==null) { showAiSetup(); return }
        if (plannerSettings.environmentConsent) { scanEnvironment(online = true); return }
        AlertDialog.Builder(this).setTitle("Observe the scene for AI planning?")
            .setMessage("A short sweep collects six reduced views locally. Up to four views are sent to Google for scene and viewpoint assessment using your saved research key. " +
                "Google's free tier may use submitted images to improve its products. Your project's quota applies. " +
                "Relative depth estimates may be included with those images.")
            .setPositiveButton("Allow and scan") { _, _ -> plannerSettings.consentToEnvironmentUpload(); scanEnvironment(online = true) }
            .setNegativeButton(R.string.dismiss,null).show()
    }

    private fun scanEnvironment(online: Boolean = false) {
        if (!cameraReady || scanning || saving) return
        cancelGuide(false)
        val token = generation
        val requestId = if (online) planner.beginRequest() else null
        baselineZoom = controller?.zoomState?.value?.zoomRatio ?: 1f
        scanning = true
        guidance.setText(R.string.sweep_instruction)
        environmentSweep = EnvironmentSweep(analyzer,
            { controller?.zoomState?.value?.zoomRatio ?: 1f }, { controller?.zoomState?.value?.maxZoomRatio ?: 1f },
            { batch ->
                environmentSweep = null
                when {
                    generation != token || isDestroyed -> batch?.close()
                    batch == null -> {
                        analyzer.endEnvironment()
                        scanning = false
                        guidance.setText(R.string.sweep_no_frame)
                    }
                    else -> runEnvironmentPlanner(batch, token, requestId)
                }
            })
        environmentSweep!!.start()
    }
    private fun runEnvironmentPlanner(batch: EnvironmentBatch, token: Int, requestId: Long?) {
        guidance.setText(R.string.sweep_assessing)
        val started = SystemClock.elapsedRealtime()
        val availableZoom = ((controller?.zoomState?.value?.maxZoomRatio ?: baselineZoom) / baselineZoom).coerceIn(1f, 3.2f)
        modelExecutor.execute {
            val result = runCatching { localComposition.evaluate(batch, availableZoom) { generation == token } }
            val optionalOnline = if (requestId != null && generation == token) runCatching {
                planner.chooseEnvironment(batch, requestId, availableZoom, result.getOrNull()?.relativeDepth.orEmpty())
            }.getOrNull() else null
            handler.post {
                if (generation != token || isDestroyed) { batch.close(); return@post }
                scanning = false
                lastScanMs = SystemClock.elapsedRealtime() - started
                val local = result.getOrNull()
                val decision = optionalOnline?.decision ?: local?.decision
                modelReason = "Observed ${batch.frames.size} views, ${batch.map.registeredLinks} overlap links, " +
                    "${local?.relativeDepth?.size ?: 0} relative depth estimates. " +
                    (if (optionalOnline != null) "Online assessment." else "On-device assessment.")
                if (decision == null) {
                    guidance.setText(R.string.framing_unavailable)
                } else if (!analyzer.environmentFresh(SystemClock.elapsedRealtime())) {
                    guidance.setText(R.string.sweep_scene_changed)
                } else when (decision) {
                    is EnvironmentDecision.Keep -> {
                        if (analyzer.atEnvironmentCheckpoint()) {
                            guidance.setText(R.string.sweep_keep)
                        } else {
                            showEnvironmentChoice(batch, EnvironmentDecision.Guide(batch.frames.last().view.id,
                                SubjectBox(0f, 0f, 1f, 1f), "Observed view", decision.reason), token)
                            return@post
                        }
                    }
                    is EnvironmentDecision.ObserveMore -> guidance.text = decision.reason
                    is EnvironmentDecision.Guide -> {
                        showEnvironmentChoice(batch, decision, token)
                        return@post
                    }
                }
                analyzer.endEnvironment()
                batch.close()
            }
        }
    }
    private fun showEnvironmentChoice(batch: EnvironmentBatch, decision: EnvironmentDecision.Guide, token: Int) {
        val route=batch.map.routeTo(decision.viewId)
        if(route==null) {
            analyzer.endEnvironment(); batch.close(); guidance.text="The views have insufficient overlap for a reliable return guide. Use a shorter sweep."
            return
        }
        val selected=batch.frames.single { it.view.id==decision.viewId }.frame
        val current=batch.frames.last().frame
        val b=decision.crop
        val region=Bitmap.createBitmap(selected.image,(b.left*selected.image.width).toInt(),(b.top*selected.image.height).toInt(),
            ((b.right-b.left)*selected.image.width).toInt().coerceAtLeast(1),((b.bottom-b.top)*selected.image.height).toInt().coerceAtLeast(1))
        val before=Bitmap.createScaledBitmap(current.image,180,240,true)
        val after=Bitmap.createScaledBitmap(region,180,240,true)
        if (region!==after && region!==selected.image) region.recycle()
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,8,16,8) }
        panel.addView(TextView(this).apply { text="${decision.reason}\nScanned view on the left. Suggested shot on the right." })
        panel.addView(LinearLayout(this).apply {
            addView(ImageView(this@MainActivity).apply { setImageBitmap(before); adjustViewBounds=true; contentDescription="Current observation" },LinearLayout.LayoutParams(0,-2,1f))
            addView(ImageView(this@MainActivity).apply { setImageBitmap(after); adjustViewBounds=true; contentDescription="Selected observed view" },LinearLayout.LayoutParams(0,-2,1f))
        })
        val shownAt=SystemClock.elapsedRealtime()
        var applied=false
        var stale=false
        val dialog=AlertDialog.Builder(this).setTitle("Suggested shot").setView(panel)
            .setPositiveButton("Use this shot") { _, _ ->
                if (token==generation && SystemClock.elapsedRealtime()-shownAt<15_000 && analyzer.environmentFresh(SystemClock.elapsedRealtime())) {
                    applied=true; analyzer.endEnvironment()
                    activeEnvironmentBatch=batch
                    for(id in route) {
                        val entry=batch.frames.single { it.view.id==id }
                        val crop=if(id==decision.viewId) decision.crop else SubjectBox(0f,0f,1f,1f)
                        environmentRoute.addLast(entry.frame to ModelChoice(-1,decision.reason,crop))
                    }
                    advanceEnvironmentRoute()
                } else { stale=true; guidance.text="The observation is stale. Scan the surroundings again." }
            }.setNegativeButton("Dismiss",null).create()
        dialog.setOnDismissListener {
            before.recycle(); after.recycle(); if(!applied) batch.close()
            if (environmentDialog===dialog) environmentDialog=null
            if (token==generation) { analyzer.endEnvironment(); if (!applied && !stale) guidance.setText(R.string.ready_to_frame) }
        }
        environmentDialog=dialog; dialog.show()
        handler.postDelayed({
            if(environmentDialog===dialog && token==generation) {
                stale=true; guidance.text="The observation expired. Scan the surroundings again."; dialog.dismiss()
            }
        },15000)
    }
    private fun advanceEnvironmentRoute() {
        val next=environmentRoute.removeFirstOrNull() ?: return
        applyChoice(next.first,next.second)
        if(environmentRoute.isNotEmpty()) {
            autoZoom=false; guidance.text="Follow the recorded views toward the selected shot."
        }
    }

    private fun scan(manual: Boolean, local: Boolean = false) {
        if (!cameraReady || scanning || saving) return
        cancelGuide(false)
        val token = ++generation
        val cloudRequest = planner.beginRequest()
        scanning = true; baselineZoom = controller?.zoomState?.value?.zoomRatio ?: 1f
        val maxRelativeZoom = ((controller?.zoomState?.value?.maxZoomRatio ?: baselineZoom) / baselineZoom).coerceIn(1f, 1.8f)
        guidance.setText(if (local) R.string.finding_frame else R.string.finding_online_frame)
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
                val result = runCatching {
                    if (local) {
                        val ranked = localScorer.evaluate(frame.image, maxRelativeZoom) { inferenceToken == generation }
                        ModelChoice(ranked.crop.id, "Local crop ranking · ${ranked.elapsedMs} ms")
                    } else planner.choose(frame.image, cloudRequest, maxRelativeZoom) { stage -> handler.post {
                        if (inferenceToken == generation && !isDestroyed) guidance.text = stage
                    } }
                }
                handler.post {
                    if (inferenceToken != generation || isDestroyed) { frame.image.recycle(); return@post }
                    scanning = false; lastScanMs = SystemClock.elapsedRealtime() - started
                    result.onSuccess { showFramingChoice(frame, it, inferenceToken) }.onFailure { error ->
                        frame.image.recycle()
                        modelReason = if (local) "Local scorer failed: ${error.javaClass.simpleName}" else "Online scan failed; see scan diagnostics."
                        guidance.setText(when {
                            local -> R.string.framing_unavailable
                            (error as? SceneServiceException)?.category == "timeout" -> R.string.online_timeout
                            else -> R.string.online_unavailable
                        })
                    }
                }
            }
        } }
    }

    private fun showFramingChoice(frame: ScanFrame, choice: ModelChoice, token: Int) {
        modelReason = choice.explanation
        if (!FramingFeedback.hasSuggestion(choice)) {
            frame.image.recycle()
            guidance.setText(R.string.no_framing_suggestion)
            return
        }
        val box = choice.customCrop ?: ShotGeometry.options[choice.cropId].box
        val selected = Bitmap.createBitmap(frame.image, (box.left * frame.image.width).toInt(),
            (box.top * frame.image.height).toInt(), ((box.right-box.left)*frame.image.width).toInt(),
            ((box.bottom-box.top)*frame.image.height).toInt())
        val before = Bitmap.createScaledBitmap(frame.image, 180, 240, true)
        val after = Bitmap.createScaledBitmap(selected, 180, 240, true)
        if (selected !== after && selected !== frame.image) selected.recycle()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }
        panel.addView(TextView(this).apply { setText(R.string.compare_framing) })
        panel.addView(LinearLayout(this).apply {
            addView(ImageView(this@MainActivity).apply { setImageBitmap(before); adjustViewBounds = true }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(this@MainActivity).apply { setImageBitmap(after); adjustViewBounds = true }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        AlertDialog.Builder(this).setTitle(R.string.suggested_framing).setView(panel)
            .setPositiveButton(R.string.use_frame) { _, _ ->
                if (generation == token) applyChoice(frame, choice) else frame.image.recycle()
            }.setNegativeButton(R.string.dismiss) { _, _ ->
                frame.image.recycle(); guidance.setText(R.string.ready_to_frame)
            }.setOnCancelListener { frame.image.recycle(); guidance.setText(R.string.ready_to_frame) }
            .setOnDismissListener { before.recycle(); after.recycle() }.show()
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
        hold.reset(); overlay.aligned=false; overlay.fresh=false; overlay.frame=null
        val crop = choice.customCrop?.let { CropOption(-1, it) } ?: ShotGeometry.options[choice.cropId]
        val b = crop.box
        planZoomLimit = maxOf(1.8f,crop.zoom*1.03f).coerceAtMost(3.2f)
        overlay.targetPreview?.recycle()
        val selected = Bitmap.createBitmap(frame.image, (b.left * frame.image.width).toInt(),
            (b.top * frame.image.height).toInt(), ((b.right - b.left) * frame.image.width).toInt(),
            ((b.bottom - b.top) * frame.image.height).toInt())
        overlay.targetPreview = Bitmap.createScaledBitmap(selected, 180, 240, true)
        if (selected !== frame.image && selected !== overlay.targetPreview) selected.recycle()
        activePlanId = tracker.select(frame, crop)
        modelReason = choice.explanation; activePlan = true; autoZoom = true; lastGood = null
        guideLifetime.start(SystemClock.elapsedRealtime())
        guidance.text = "Move toward the outlined frame."
        telemetry.setText(R.string.guide_controls)
        frame.image.recycle(); refreshButtons()
    }

    private fun showTrack(tracked: TrackedFrame) {
        if (SystemClock.elapsedRealtime() - tracked.timeMs > 350) { trackingLost(); return }
        lastGood = tracked; guideLifetime.observed(tracked.timeMs)
        overlay.frame = tracked.corners; overlay.fresh = true
        val aligned = hold.update(ShotGeometry.framingReady(tracked.corners), tracked.timeMs)
        if(aligned && environmentRoute.isNotEmpty()) { advanceEnvironmentRoute(); return }
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
            val desired = ShotGeometry.desiredZoom(zoom.zoomRatio, tracked.corners, baselineZoom, zoom.maxZoomRatio,planZoomLimit) ?: return
            if (abs(desired - zoom.zoomRatio) > .025f) {
                val next = zoom.zoomRatio + (desired - zoom.zoomRatio).coerceIn(-.04f, .04f)
                zoomBusy = true
                val future = camera.setZoomRatio(next)
                future.addListener({ zoomBusy = false; runCatching { future.get() }.onFailure { autoZoom = false } }, main)
            }
        }
    }
    private fun trackingLost() {
        if (guideLifetime.expired(SystemClock.elapsedRealtime())) {
            cancelGuide(false)
            guidance.text = "The scene changed or tracking was lost. Find a new composition."
            return
        }
        hold.reset(); overlay.aligned = false; overlay.fresh = false
        val age = lastGood?.let { SystemClock.elapsedRealtime() - it.timeMs } ?: Long.MAX_VALUE
        if (age > 600) overlay.frame = null
        overlay.invalidate()
        guidance.text = if (age < 600) "Holding your shot plan…" else "Return to the scanned scene, or find another shot."
    }
    private fun cancelGuide(resetZoom: Boolean) {
        generation++
        environmentDialog?.dismiss(); environmentDialog=null
        environmentRoute.clear(); activeEnvironmentBatch?.close(); activeEnvironmentBatch=null
        environmentSweep?.cancel(); environmentSweep=null; analyzer.endEnvironment()
        sweepRecorder?.cancel(); sweepRecorder = null
        planner.cancel(); analyzer.cancelSnapshot(); tracker.reset()
        scanning = false; activePlan = false; lastGood = null; hold.reset()
        overlay.frame = null; overlay.aligned = false; overlay.fresh = false
        overlay.targetPreview?.recycle(); overlay.targetPreview = null; overlay.invalidate()
        if (resetZoom) { controller?.setZoomRatio(baselineZoom); guidance.setText(R.string.ready_to_frame) }
        refreshButtons()
    }

    private fun requestComposition() {
        if (plannerSettings.key() == null) { showAiSetup(); return }
        if (plannerSettings.consent) { scan(false); return }
        AlertDialog.Builder(this).setTitle("Send one scene image to Google?")
            .setMessage("Each Cloud research scan sends one reduced camera image to Google's Gemini API. " +
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
                    .onSuccess { keyInput.text.clear(); dialog.dismiss(); guidance.text = "Setup saved for the online tools." }
                    .onFailure { keyInput.error = it.message }
            }
        }
        dialog.show()
    }
    private fun showOptions() {
        if (saving) return
        if (scanning) { cancelGuide(false); guidance.setText(R.string.ready_to_frame) }
        AlertDialog.Builder(this).setTitle(R.string.options)
            .setItems(arrayOf(getString(R.string.photo_processing), getString(R.string.about_build), getString(R.string.developer_tools))) { _, index ->
                when (index) {
                    0 -> chooseProcessing()
                    1 -> AlertDialog.Builder(this).setTitle(R.string.about_build).setMessage(R.string.about_framing)
                        .setPositiveButton(R.string.close, null).show()
                    2 -> showDeveloperTools()
                }
            }.show()
    }
    private fun showDeveloperTools() {
        AlertDialog.Builder(this).setTitle("Camera options").setItems(arrayOf("AI setup", "Try the framing guide without AI",
            "Photo processing", "Camera and scan details", "Google privacy and pricing", "Forget saved API key",
            "Scan diagnostics", "Test service with a generated image", "Camera capability report",
            "Cloud research scan (personal key)", "Record a local evaluation sweep", "Export last evaluation sweep", "Legacy local crop research",
            "Test spatial tracking (ARCore)", "Online sweep (personal key)")) { _, index ->
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
                6 -> showReport("Scan diagnostics", "$lastDiagnostic\n\n${analyzer.stats.report()}\n" +
                    "Camera Compositor ${BuildConfig.VERSION_NAME}; Android ${Build.VERSION.SDK_INT}\n" +
                    "Event times are milliseconds from the start of the request. The report contains no key or scene image.")
                7 -> confirmDiagnostic()
                8 -> showReport("Camera capabilities", runCatching { CameraCapabilityReport.collect(this) }.getOrDefault(
                    "Camera capabilities could not be read. Allow camera access and try again.") +
                    "\n${quality.status}\nRear extensions: ${quality.supported(CameraSelector.DEFAULT_BACK_CAMERA).joinToString { it.second }}\n" +
                    "Front extensions: ${quality.supported(CameraSelector.DEFAULT_FRONT_CAMERA).joinToString { it.second }}\n$lastPhotoDetails")
                9 -> requestComposition()
                10 -> confirmSweep()
                11 -> if (pendingSweep?.exists() == true) exportSweep.launch("CameraCompositor-observed-views.zip")
                    else showReport("Evaluation sweep", "Record a local evaluation sweep first.")
                12 -> if (localScorer.available) scan(false,local=true) else guidance.setText(R.string.framing_unavailable)
                13 -> launchSpatialProbe()
                14 -> requestOnlineEnvironmentScan()
            }
        }.show()
    }
    private fun handoffCamera(open: () -> Unit) {
        val camera=controller ?: return
        val info=camera.cameraInfo ?: return
        val token=++generation
        camera.clearImageAnalysisAnalyzer(); camera.unbind(); controller=null
        preview.controller=null; cameraReady=false; guidance.text="Releasing the camera for spatial tracking…"
        var launched=false
        val observer=object: androidx.lifecycle.Observer<CameraState> {
            override fun onChanged(value: CameraState) {
                if(token!=generation || isDestroyed) { info.cameraState.removeObserver(this); return }
                if(value.type==CameraState.Type.CLOSED) {
                    info.cameraState.removeObserver(this); launched=true
                    runCatching { open() }.onFailure { startCamera() }
                }
            }
        }
        info.cameraState.observe(this,observer)
        handler.postDelayed({
            if(!launched && token==generation && !isDestroyed) {
                info.cameraState.removeObserver(observer); startCamera()
                guidance.text="Camera handoff did not finish. The normal camera is restarting."
            }
        },3000)
    }
    private fun launchSpatialProbe() {
        if (!cameraReady || saving) return
        cancelGuide(false)
        AlertDialog.Builder(this).setTitle("Optional spatial tracking test")
            .setMessage("This tests camera pose, sparse 3D points and depth where supported. Google Play Services for AR (ARCore) provides this functionality under Google's Terms of Service and Privacy Policy. " +
                "Google may require installing or updating its AR service. CameraX pauses during this separate test. No camera images are exported by this test.")
            .setPositiveButton("Continue") { _, _ ->
                handoffCamera { startActivity(Intent(this,SpatialProbeActivity::class.java)) }
            }.setNeutralButton("Terms and privacy") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://developers.google.com/ar/develop/terms"))) }
            .setNegativeButton("Cancel",null).show()
    }
    private fun confirmSweep() {
        if (!cameraReady || saving || scanning) return
        AlertDialog.Builder(this).setTitle("Record observed views locally?")
            .setMessage("Slowly turn across a small area for about four seconds. Eight reduced views and relative rotation readings are saved for research. " +
                "Keep the zoom fixed. Nothing is uploaded. This recording helps evaluate actual angle selection separately from cropping.")
            .setPositiveButton("Record sweep") { _, _ ->
                cancelGuide(false)
                val token = ++generation
                scanning = true; refreshButtons()
                sweepRecorder = ResearchSweepRecorder(this, analyzer, modelExecutor,
                    { controller?.zoomState?.value?.zoomRatio ?: 1f }, { controller?.zoomState?.value?.maxZoomRatio ?: 1f },
                    baseSelector == CameraSelector.DEFAULT_FRONT_CAMERA,
                    { count -> if (!isDestroyed && generation == token) guidance.text = "Recording observed views: $count of 8" },
                    { file -> if (!isDestroyed && generation == token) {
                        scanning = false; sweepRecorder = null; refreshButtons()
                        pendingSweep = file
                        guidance.text = if (file == null) "Recording stopped. Try again with the camera visible." else "Research sweep ready. Export it in Options when convenient."
                    } })
                guidance.text = "Turn slowly across the scene. Keep the zoom fixed."
                sweepRecorder?.start()
            }.setNegativeButton("Cancel", null).show()
    }
    private fun showReport(title: String, report: String) {
        val text = TextView(this).apply { this.text = report; setTextIsSelectable(true); setPadding(24, 12, 24, 12) }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Close", null).setNeutralButton("Copy report") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(title, report))
            }.show()
    }
    private fun confirmDiagnostic() {
        if (scanning || saving) return
        if (plannerSettings.key() == null) { showAiSetup(); return }
        AlertDialog.Builder(this).setTitle("Test the scene service?")
            .setMessage("This sends a generated test chart to Google using the same request format as a scene scan. " +
                "Your camera image stays local. It uses one scan attempt from your research allowance. " +
                "The result checks connectivity and response parsing. Composition quality needs separate evaluation.")
            .setPositiveButton("Send test chart") { _, _ -> runDiagnostic() }.setNegativeButton("Cancel", null).show()
    }
    private fun runDiagnostic() {
        cancelGuide(false)
        val token = ++generation; val requestId = planner.beginRequest()
        scanning = true; refreshButtons(); guidance.text = "Testing the scene service…"
        modelExecutor.execute {
            val chart = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(chart); val paint = android.graphics.Paint()
            canvas.drawColor(android.graphics.Color.rgb(120, 180, 230))
            paint.color = android.graphics.Color.rgb(70, 110, 70); canvas.drawRect(0f, 430f, 480f, 640f, paint)
            paint.color = android.graphics.Color.rgb(90, 70, 50); canvas.drawRect(300f, 200f, 325f, 500f, paint)
            paint.color = android.graphics.Color.rgb(40, 100, 50); canvas.drawCircle(310f, 170f, 90f, paint)
            val result = try { runCatching { planner.testService(chart, requestId) } } finally { chart.recycle() }
            handler.post {
                if (!isDestroyed && generation == token) {
                    scanning = false; refreshButtons()
                    guidance.text = if (result.isSuccess) "Test-image request succeeded. Review the scan diagnostics." else "Test-image request failed. Review the scan diagnostics."
                    showReport("Test-image result", (result.exceptionOrNull()?.message ?: "Connectivity and composition-response parsing succeeded.") + "\n\n$lastDiagnostic")
                }
            }
        }
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
        val next = if (baseSelector != CameraSelector.DEFAULT_FRONT_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!camera.hasCamera(next)) { toast(R.string.no_other_camera); return }
        cancelGuide(false)
        val previous = baseSelector
        try { camera.cameraSelector = next; baseSelector = next; updateCameraStatus() }
        catch (_: Exception) { camera.cameraSelector = previous; toast(R.string.no_other_camera) }
    }

    private fun takePhoto() {
        val camera = controller ?: return
        if (!cameraReady || saving) return
        // A photograph takes priority over any background scan, even an online timeout.
        if (scanning) cancelGuide(false)
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
                zoomRequest = camera.setZoomRatio(zoom)
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
        renderControls(interaction.controls)
    }
    private fun renderControls(controls: CameraControls) {
        if (!::find.isInitialized) return
        capture.isEnabled = controls.captureEnabled
        find.isEnabled = controls.findEnabled
        flip.isEnabled = controls.flipEnabled
        cancel.setText(if (interaction.scanning) R.string.cancel_scan else R.string.cancel)
        cancel.visibility = if (controls.cancelVisible) View.VISIBLE else View.GONE
    }
    private fun toast(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_SHORT).show()
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("lastPhoto", lastPhoto?.toString()); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        environmentRoute.clear(); activeEnvironmentBatch?.close(); activeEnvironmentBatch=null
        environmentSweep?.cancel(); environmentDialog?.dismiss(); analyzer.endEnvironment()
        generation++; handler.removeCallbacksAndMessages(null); analyzer.cancelSnapshot()
        controller?.clearImageAnalysisAnalyzer(); controller?.unbind(); planner.cancel()
        analysisExecutor.execute { tracker.close(); analyzer.closeEnvironment() }; analysisExecutor.shutdown()
        modelExecutor.execute { planner.close(); localScorer.close(); softwareDepth.close() }; modelExecutor.shutdown()
        super.onDestroy()
    }
}
