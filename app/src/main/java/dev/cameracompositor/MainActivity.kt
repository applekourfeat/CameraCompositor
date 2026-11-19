package dev.cameracompositor

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
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
    private lateinit var findShot: Button
    private lateinit var automatic: Button
    private lateinit var countdown: TextView
    private lateinit var cancel: Button
    private lateinit var flip: Button
    private lateinit var plannerSettings: PlannerSettings
    private lateinit var planner: CloudShotPlanner
    private lateinit var localScorer: LocalResearchScorer
    private lateinit var softwareDepth: SoftwareDepth
    private lateinit var subjectDetector: SceneSubjectDetector
    private lateinit var sceneRecognition: SceneRecognition
    private lateinit var localComposition: LocalComposition
    private val contextualScorer by lazy { LocalResearchScorer(this, "gaic-dynamic.onnx") }
    private lateinit var contextComposition: ContextComposition
    private lateinit var quality: CameraQuality
    private var sweepRecorder: ResearchSweepRecorder? = null
    private var environmentSweep: EnvironmentSweep? = null
    private var environmentDialog: AlertDialog? = null
    private var activeEnvironmentBatch: EnvironmentBatch? = null
    private val environmentRoute=ArrayDeque<Pair<ScanFrame,ModelChoice>>()
    private var plannedZoom = 1f
    private var aimTolerance = ShotGeometry.DEFAULT_AIM_TOLERANCE
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
    private val motion by lazy { MotionOrientation(this) }
    private lateinit var analyzer: SceneFrameAnalyzer
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val main by lazy { ContextCompat.getMainExecutor(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val zoomAnimator by lazy { CameraZoomAnimator(handler, main) }
    private val photoCapture by lazy { PhotoCapturePipeline(this, handler, main) }
    private val hold = AlignmentHold()
    private val waypointHold = AlignmentHold(250)
    private val guideLifetime = GuideLifetime(6_500)
    private val automaticShutter = AutomaticShutter()
    private val automaticScan = AutomaticScanGate()
    private var autoShutter = true
    private var shutterEligible = false
    private var autoPausedUntil = 0L
    private var exploration: EnvironmentDecision.Explore? = null
    private val explorationHold = AlignmentHold(220)
    private var latestGuideFrameAt = 0L
    private var automaticGuideAt = 0L
    private var scanReference: LiveScene? = null
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
    private var baselineZoom = 1f
    private var lastGood: TrackedFrame? = null
    private var lastPhoto: Uri? = null
    private var modelReason = ""
    private var lastScanMs = 0L
    private var lastPhotoDetails = "No photo taken in this session"
    private val cameraPreferences by lazy { getSharedPreferences("camera_preferences", MODE_PRIVATE) }
    private var photoMode = PhotoMode.JPEG
    private var pendingSubject: SubjectTap? = null
    private var checkOpeningZoom = BuildConfig.VIVO_DEMO
    private var framingInstruction: String? = null
    private var zoomTrimmed = false
    private var verifyingUntil = 0L

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) startCamera() else { guidance.setText(R.string.camera_permission); access.visibility = View.VISIBLE }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (activePlan && !saving && cameraForeground()) {
                if (verifyingUntil != 0L && now >= verifyingUntil) trackingLost()
                else if (automaticGuideAt != 0L && now - automaticGuideAt > 15_000) abandonAutomaticGuide()
                else if (now - latestGuideFrameAt > 300) trackingLost()
            }
            if (cameraReady && !scanning && !activePlan && !saving && now >= autoPausedUntil && cameraForeground() &&
                preview.previewStreamState.value == PreviewView.StreamState.STREAMING && automaticScan.ready(analyzer.liveScene, now)) {
                if (!openWideIfSupported(now)) scanEnvironment(autoScan = true)
            }
            handler.postDelayed(this, 150)
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
        cancel = findViewById(R.id.cancel); flip = findViewById(R.id.flip)
        automatic = findViewById(R.id.automatic); countdown = findViewById(R.id.shutter_countdown)
        findShot = findViewById(R.id.find_shot)
        findShot.setOnClickListener {
            checkOpeningZoom = false
            automaticScan.reset()
            scanEnvironment(autoScan = true)
        }
        val savedPhotoMode = cameraPreferences.getString("photo_mode", null)
        photoMode = PhotoMode.entries.firstOrNull { it.name == savedPhotoMode } ?: PhotoMode.JPEG
        autoShutter = cameraPreferences.getBoolean("auto_shutter", true)
        plannerSettings = PlannerSettings(this)
        localScorer = LocalResearchScorer(this)
        softwareDepth = SoftwareDepth(this)
        subjectDetector = SceneSubjectDetector(this)
        sceneRecognition = SceneRecognition(this)
        localComposition = LocalComposition(softwareDepth, subjectDetector, sceneRecognition)
        contextComposition = ContextComposition(sceneRecognition, subjectDetector,
            if (BuildConfig.VIVO_DEMO) contextualScorer else null)
        lastDiagnostic = runCatching { java.io.File(filesDir, "last-scan-diagnostic.json").readText() }.getOrDefault(lastDiagnostic)
        planner = CloudShotPlanner(plannerSettings) { report ->
            lastDiagnostic = report
            runCatching { java.io.File(filesDir, "last-scan-diagnostic.json").writeText(report) }
        }
        analyzer = SceneFrameAnalyzer(tracker, motion) { event -> handler.post {
            if (cameraForeground() && activePlan && !saving && event.planId == activePlanId) {
                if (event.frame == null) trackingLost() else showTrack(event.frame)
            }
        } }
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            analyzer.viewSize = Size(preview.width, preview.height)
            motion.displayRotation = preview.display?.rotation ?: Surface.ROTATION_0
        }
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                pickSubject(e.x, e.y)
                return true
            }
        })
        var pinch = false
        preview.setOnTouchListener { _, event ->
            if (saving) true else {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) pinch = false
                if (event.pointerCount > 1) {
                    checkOpeningZoom = false
                    if (!pinch) {
                        clearSubjectTap()
                        cancelGuide()
                        automaticScan.reset()
                        guidance.setText(R.string.ready_to_frame)
                    }
                    pinch = true
                    autoPausedUntil = SystemClock.elapsedRealtime() + 760
                }
                if (!pinch) taps.onTouchEvent(event)
                false
            }
        }
        quality = CameraQuality(this) { if (!isDestroyed) updateCameraStatus() }
        lastPhoto = savedInstanceState?.getString("lastPhoto")?.let(Uri::parse)
        gallery.isEnabled = lastPhoto != null
        access.setOnClickListener { requestCameraAccess() }
        capture.setOnClickListener { takePhoto() }; gallery.setOnClickListener { openLastPhoto() }
        automatic.setOnClickListener {
            autoShutter = !autoShutter
            cameraPreferences.edit().putBoolean("auto_shutter", autoShutter).apply()
            automaticShutter.reset()
            updateCountdown(null)
            refreshButtons()
        }
        cancel.setOnClickListener {
            cancelGuide()
            waitForAnotherScene()
        }
        flip.setOnClickListener { flipCamera() }
        findViewById<Button>(R.id.settings).setOnClickListener { showOptions() }
        if (hasPermission()) startCamera() else permissionRequest.launch(Manifest.permission.CAMERA)
        refreshButtons()
    }

    override fun onResume() {
        super.onResume(); motion.start(); handler.post(tick)
        if (::analyzer.isInitialized && hasPermission() && controller == null) startCamera()
    }
    override fun onPause() {
        handler.removeCallbacks(tick)
        motion.stop()
        if (::analyzer.isInitialized) {
            clearSubjectTap()
            cancelGuide()
        }
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::countdown.isInitialized) {
            automaticShutter.reset()
            updateCountdown(null)
            zoomAnimator.stop()
        }
    }
    private fun cameraForeground() = !isDestroyed && hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun openWideIfSupported(now: Long): Boolean {
        if (!checkOpeningZoom) return false
        checkOpeningZoom = false
        if (baseSelector != CameraSelector.DEFAULT_BACK_CAMERA) return false
        val camera = controller ?: return false
        val zoom = camera.zoomState.value ?: return false
        if (zoom.minZoomRatio !in .42f.. .94f || zoom.zoomRatio <= zoom.minZoomRatio + .02f) return false
        val change = runCatching { camera.setZoomRatio(zoom.minZoomRatio) }.getOrNull() ?: return false
        autoPausedUntil = now + 750
        guidance.setText(R.string.opening_wide)
        change.addListener({
            if (controller === camera && !isDestroyed && runCatching { change.get() }.isSuccess) {
                autoPausedUntil = maxOf(autoPausedUntil, SystemClock.elapsedRealtime() + 260)
            }
        }, main)
        return true
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
            camera.setImageAnalysisAnalyzer(analysisExecutor, ThrottledAnalyzer(analyzer) {
                when {
                    analyzer.observingEnvironment -> 33L
                    analyzer.trackingGuide -> 50L
                    else -> 100L
                }
            })
            preview.controller = camera; camera.cameraSelector = baseSelector; camera.bindToLifecycle(this)
            camera.zoomState.observe(this) { if (controller === camera && it != null) motion.zoom = it.zoomRatio }
            camera.initializationFuture.addListener({
                if (controller !== camera || isDestroyed) return@addListener
                try {
                    camera.initializationFuture.get(); cameraReady = true; access.visibility = View.GONE
                    motion.configure(camera.cameraInfo, baseSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                    guidance.setText(R.string.ready_to_frame)
                    refreshButtons(); updateCameraStatus()
                } catch (_: Exception) { cameraFailed(camera) }
            }, main)
        } catch (_: Exception) { cameraFailed(camera) }
    }
    private fun cameraFailed(camera: LifecycleCameraController) {
        cancelGuide()
        motion.configure(null, false)
        camera.clearImageAnalysisAnalyzer(); camera.unbind(); if (controller === camera) controller = null
        cameraReady = false; refreshButtons(); guidance.setText(R.string.camera_error); access.visibility = View.VISIBLE
    }

    private fun waitForAnotherScene(saved: Boolean = false, reference: LiveScene? = analyzer.liveScene, message: String? = null) {
        automaticScan.waitForChange(SystemClock.elapsedRealtime(), saved, reference, scanReference)
        guidance.text = message ?: getString(if (saved) R.string.auto_next_scene else R.string.auto_waiting)
    }

    private fun pickSubject(x: Float, y: Float) {
        val observation = analyzer.liveScene ?: return
        val zoom = controller?.zoomState?.value?.zoomRatio ?: return
        if (!cameraReady || saving || preview.width == 0 || preview.height == 0 ||
            SystemClock.elapsedRealtime() - observation.timeMs > 400) return
        cancelGuide()
        val point = ScenePoint((x / preview.width).coerceIn(0f, 1f), (y / preview.height).coerceIn(0f, 1f))
        pendingSubject = SubjectTap(point, observation, zoom)
        overlay.subjectPin = point
        automaticScan.reset()
        guidance.setText(R.string.subject_selected)
    }

    private fun clearSubjectTap() {
        pendingSubject = null
        overlay.subjectPin = null
    }

    private fun abandonAutomaticGuide() {
        cancelGuide()
        retryAutomatic()
    }

    private fun retryAutomatic(message: Int = R.string.guide_recovering) {
        if (automaticScan.retryOnce(SystemClock.elapsedRealtime())) guidance.setText(message)
        else waitForAnotherScene(message = getString(R.string.find_again))
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

    private fun scanEnvironment(online: Boolean = false, autoScan: Boolean = false,
                                completedTurn: EnvironmentDecision.Explore? = null) {
        if (!cameraReady || scanning || saving || (autoScan && !cameraForeground())) return
        cancelGuide()
        val token = generation
        val requestId = if (online) planner.beginRequest() else null
        val tap = pendingSubject.takeUnless { online }
        clearSubjectTap()
        baselineZoom = controller?.zoomState?.value?.zoomRatio ?: 1f
        if (autoScan) scanReference = analyzer.liveScene
        scanning = true
        guidance.setText(if (autoScan) R.string.checking_composition else R.string.sweep_instruction)
        environmentSweep = EnvironmentSweep(analyzer,
            { controller?.zoomState?.value?.zoomRatio ?: 1f }, { controller?.zoomState?.value?.maxZoomRatio ?: 1f },
            frameLimit = if (autoScan) 1 else 6) { batch ->
                environmentSweep = null
                when {
                    generation != token || isDestroyed -> batch?.close()
                    batch == null -> {
                        analyzer.endEnvironment()
                        scanning = false
                        modelReason = "The camera did not return a complete analysis frame."
                        if (autoScan) retryAutomatic() else guidance.setText(R.string.sweep_no_frame)
                    }
                    else -> runEnvironmentPlanner(batch, token, requestId, autoScan, completedTurn, tap)
                }
            }
        environmentSweep!!.start()
    }
    private fun runEnvironmentPlanner(batch: EnvironmentBatch, token: Int, requestId: Long?, autoScan: Boolean,
                                      completedTurn: EnvironmentDecision.Explore?, tap: SubjectTap?) {
        guidance.setText(if (autoScan) R.string.checking_composition else R.string.sweep_assessing)
        val started = SystemClock.elapsedRealtime()
        val availableZoom = ((controller?.zoomState?.value?.maxZoomRatio ?: baselineZoom) / baselineZoom).coerceIn(1f, 3.2f)
        modelExecutor.execute {
            val result = runCatching {
                if (autoScan) {
                    contextComposition.evaluate(batch, availableZoom, tap, completedTurn == null,
                        completedTurn?.expected) { generation == token }
                } else {
                    localComposition.evaluate(batch, availableZoom, CompositionStyle.THIRDS, tap,
                        false, null) { generation == token }
                }
            }
            val optionalOnline = if (requestId != null && generation == token) runCatching {
                planner.chooseEnvironment(batch, requestId, availableZoom, result.getOrNull()?.relativeDepth.orEmpty())
            }.getOrNull() else null
            handler.post {
                if (generation != token || isDestroyed) { batch.close(); return@post }
                scanning = false
                lastScanMs = SystemClock.elapsedRealtime() - started
                val local = result.getOrNull()
                val decision = optionalOnline?.decision ?: local?.decision
                val reference = batch.frames.last()
                val handoff = if (autoScan) LiveShotHandoff.assess(reference.frame.scene, analyzer.liveScene,
                    reference.view.zoom, controller?.zoomState?.value?.zoomRatio ?: baselineZoom,
                    SystemClock.elapsedRealtime()) else null
                modelReason = "Observed ${batch.frames.size} views, ${batch.map.registeredLinks} overlap links, " +
                    "${local?.relativeDepth?.size ?: 0} relative depth estimates. " +
                    (if (optionalOnline != null) "Online assessment." else "On-device assessment.") +
                    (local?.recognized?.let { " Region: ${it.description}." } ?: "") +
                    (local?.comparedShots?.takeIf { it > 0 }?.let { " Compared $it frames in one view." } ?: "") +
                    (local?.modelFallback?.let { " Geometry-only ranking after model failure: $it." } ?: "") +
                    (result.exceptionOrNull()?.let { " Local analysis failed: ${it.javaClass.simpleName}." } ?: "") +
                    (handoff?.let { " Live-view handoff: ${it.name}." } ?: "")
                if (autoScan) {
                    if (!cameraForeground() || handoff == ShotHandoff.RESCAN || local == null) {
                        analyzer.endEnvironment()
                        batch.close()
                        retryAutomatic(if (local != null) R.string.recheck_shot else R.string.guide_recovering)
                        return@post
                    }
                    if (decision is EnvironmentDecision.Explore) {
                        if (handoff == ShotHandoff.VERIFY) {
                            analyzer.endEnvironment()
                            batch.close()
                            retryAutomatic(R.string.recheck_shot)
                            return@post
                        }
                        startExploration(batch, decision)
                        return@post
                    }
                    val target = decision as? EnvironmentDecision.Guide
                    framingInstruction = target?.reason
                    if (target != null && startEnvironmentGuide(batch, target, autoGuide = true,
                            allowShutter = local.canAutoCapture, handoff = handoff)) return@post
                    analyzer.endEnvironment()
                    batch.close()
                    if (handoff == ShotHandoff.VERIFY) {
                        retryAutomatic(R.string.recheck_shot)
                        return@post
                    }
                    if (local.recheckAfterSettling && automaticScan.retryOnce(SystemClock.elapsedRealtime())) {
                        guidance.setText(R.string.checking_composition)
                        return@post
                    }
                    val message = when (decision) {
                        is EnvironmentDecision.ObserveMore -> decision.reason
                        is EnvironmentDecision.Keep -> decision.reason
                        else -> null
                    }
                    waitForAnotherScene(message = message)
                    return@post
                }
                if (decision == null) {
                    guidance.setText(R.string.framing_unavailable)
                } else if (!analyzer.environmentFresh(SystemClock.elapsedRealtime())) {
                    guidance.setText(R.string.sweep_scene_changed)
                } else when (decision) {
                    is EnvironmentDecision.Keep -> {
                        if (analyzer.atEnvironmentCheckpoint()) {
                            guidance.text = decision.reason
                        } else {
                            showEnvironmentChoice(batch, EnvironmentDecision.Guide(batch.frames.last().view.id,
                                SubjectBox(0f, 0f, 1f, 1f), "Observed view", decision.reason), token)
                            return@post
                        }
                    }
                    is EnvironmentDecision.ObserveMore -> guidance.text = decision.reason
                    is EnvironmentDecision.Explore -> guidance.text = decision.reason
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
        if (batch.map.guideRouteTo(decision.viewId) == null) {
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
                    applied = startEnvironmentGuide(batch, decision, autoGuide = false)
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

    private fun startEnvironmentGuide(batch: EnvironmentBatch, decision: EnvironmentDecision.Guide, autoGuide: Boolean,
                                      allowShutter: Boolean = false, handoff: ShotHandoff? = null): Boolean {
        val route = batch.map.guideRouteTo(decision.viewId) ?: return false
        analyzer.endEnvironment()
        activeEnvironmentBatch = batch
        if (autoGuide) automaticGuideAt = SystemClock.elapsedRealtime()
        shutterEligible = autoGuide && allowShutter
        for (id in route) {
            val entry = batch.frames.single { it.view.id == id }
            val crop = if (id == decision.viewId) decision.crop else SubjectBox(0f, 0f, 1f, 1f)
            environmentRoute.addLast(entry.frame to ModelChoice(-1, decision.reason, crop))
        }
        advanceEnvironmentRoute()
        if (handoff != null) {
            verifyingUntil = SystemClock.elapsedRealtime() + LiveShotHandoff.VERIFY_WINDOW_MS
            overlay.targetVisible = handoff == ShotHandoff.SHOW
            if (handoff == ShotHandoff.SHOW && route.size == 1) {
                overlay.frame = ShotGeometry.corners(decision.crop)
                overlay.fresh = true
            } else {
                guidance.setText(R.string.checking_match)
            }
            overlay.invalidate()
        }
        return true
    }

    private fun startExploration(batch: EnvironmentBatch, decision: EnvironmentDecision.Explore) {
        val reference = batch.frames.single { it.view.id == decision.viewId }.frame
        val crop = CropOption(-1, NextView.frame(decision.aim))
        activePlanId = tracker.select(reference, crop)
        exploration = decision
        explorationHold.reset()
        analyzer.endEnvironment()
        analyzer.trackingGuide = true
        automaticGuideAt = SystemClock.elapsedRealtime()
        latestGuideFrameAt = automaticGuideAt
        guideLifetime.start(automaticGuideAt)
        overlay.aimPoint = decision.aim
        overlay.fresh = true
        overlay.invalidate()
        activePlan = true
        guidance.text = decision.reason
        batch.close()
    }

    private fun advanceEnvironmentRoute() {
        val next=environmentRoute.removeFirstOrNull() ?: return
        applyChoice(next.first,next.second)
        if(environmentRoute.isNotEmpty()) {
            guidance.text="Follow the outline toward the shot."
        }
    }

    private fun scan(manual: Boolean, local: Boolean = false) {
        if (!cameraReady || scanning || saving) return
        cancelGuide()
        val token = ++generation
        val cloudRequest = planner.beginRequest()
        scanning = true; baselineZoom = controller?.zoomState?.value?.zoomRatio ?: 1f
        val maxRelativeZoom = ((controller?.zoomState?.value?.maxZoomRatio ?: baselineZoom) / baselineZoom).coerceIn(1f, 1.8f)
        guidance.setText(if (local) R.string.finding_frame else R.string.finding_online_frame)
        handler.postDelayed({ if (token == generation && scanning) { cancelGuide(); guidance.text = "No camera frame arrived. Try again." } }, 5000)
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
        hold.reset(); waypointHold.reset(); automaticShutter.reset(); zoomAnimator.stop()
        zoomTrimmed = false
        updateCountdown(null)
        overlay.aligned=false; overlay.fresh=false; overlay.frame=null
        val crop = choice.customCrop?.let { CropOption(-1, it) } ?: ShotGeometry.options[choice.cropId]
        val b = crop.box
        aimTolerance = ShotGeometry.aimTolerance(crop, automatic = framingInstruction != null)
        overlay.aimTolerance = aimTolerance
        plannedZoom = baselineZoom * crop.zoom.coerceIn(1f, 3.2f)
        overlay.targetPreview?.recycle()
        val selected = Bitmap.createBitmap(frame.image, (b.left * frame.image.width).toInt(),
            (b.top * frame.image.height).toInt(), ((b.right - b.left) * frame.image.width).toInt(),
            ((b.bottom - b.top) * frame.image.height).toInt())
        overlay.targetPreview = Bitmap.createScaledBitmap(selected, 180, 240, true)
        if (selected !== frame.image && selected !== overlay.targetPreview) selected.recycle()
        activePlanId = tracker.select(frame, crop)
        analyzer.trackingGuide = true
        if (automaticGuideAt == 0L) modelReason = choice.explanation
        activePlan = true; autoZoom = true; lastGood = null
        guideLifetime.start(SystemClock.elapsedRealtime())
        latestGuideFrameAt = SystemClock.elapsedRealtime()
        guidance.text = framingPrompt()
        telemetry.setText(R.string.guide_controls)
        frame.image.recycle(); refreshButtons()
    }

    private fun showTrack(tracked: TrackedFrame) {
        val now = SystemClock.elapsedRealtime()
        if (now - tracked.timeMs > 300) { trackingLost(); return }
        if (verifyingUntil != 0L) {
            if (!tracked.verified || now >= verifyingUntil) { trackingLost(); return }
            verifyingUntil = 0
            overlay.targetVisible = true
            modelReason += " Image match acquired."
        }
        latestGuideFrameAt = tracked.timeMs
        val next = exploration
        if (!tracked.verified && next == null) { trackingLost(tracked.corners); return }
        if (next != null) {
            if (guideLifetime.expired(now)) { abandonAutomaticGuide(); return }
            if (tracked.verified) {
                lastGood = tracked
                guideLifetime.observed(tracked.timeMs)
            }
            val aim = ShotGeometry.center(tracked.corners)
            overlay.aimPoint = aim
            overlay.fresh = tracked.verified
            overlay.invalidate()
            if (guidance.text != next.reason) guidance.text = next.reason
            if (explorationHold.update(NextView.arrived(aim), tracked.timeMs)) {
                scanEnvironment(autoScan = true, completedTurn = next)
            }
            return
        }
        lastGood = tracked; guideLifetime.observed(tracked.timeMs)
        val waypoint = environmentRoute.isNotEmpty()
        val aiming = ShotGeometry.aimReady(tracked.corners, aimTolerance)
        val camera = controller
        if (autoZoom && !waypoint && camera != null) {
            val point = ShotGeometry.center(tracked.corners)
            if (abs(point.x - .5f) > .11f || abs(point.y - .5f) > .11f) zoomAnimator.stop()
            if (aiming) zoomAnimator.moveTo(camera, plannedZoom) { autoZoom = false }
        }
        val zoomRatio = camera?.zoomState?.value?.zoomRatio ?: plannedZoom
        if (framingInstruction != null && autoZoom && !waypoint && aiming && !zoomTrimmed && !zoomAnimator.moving &&
            tracked.timeMs - zoomAnimator.settledAt >= 250 && abs(zoomRatio / plannedZoom - 1f) < .023f && camera != null) {
            val targetWidth = ShotGeometry.width(tracked.corners)
            if (abs(targetWidth - 1f) > .085f) {
                zoomTrimmed = true
                val lower = maxOf(baselineZoom, zoomRatio * .87f)
                val upper = minOf(1.85f, baselineZoom * 3.2f, camera.zoomState.value?.maxZoomRatio ?: zoomRatio, zoomRatio * 1.13f)
                if (lower <= upper) {
                    plannedZoom = (zoomRatio / targetWidth).coerceIn(lower, upper)
                    zoomAnimator.moveTo(camera, plannedZoom) { autoZoom = false }
                }
            }
        }
        val zoomReady = !zoomAnimator.moving && tracked.timeMs - zoomAnimator.settledAt >= 80 &&
            (!autoZoom || waypoint || abs(zoomRatio / plannedZoom - 1f) < .026f)
        val framingReady = tracked.confidence >= .68f && zoomReady && ShotGeometry.framingReady(tracked.corners, aimTolerance)
        overlay.frame = tracked.corners; overlay.fresh = true
        val aligned = (if (waypoint) waypointHold else hold).update(framingReady, tracked.timeMs)
        if (aligned && waypoint) { advanceEnvironmentRoute(); return }
        if (aligned && !overlay.aligned) overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        overlay.aligned = aligned; overlay.invalidate()
        if (shutterEligible && autoShutter && !waypoint) {
            when (val state = automaticShutter.update(tracked.timeMs, now, tracked.corners, framingReady, tracked.verified)) {
                is ShutterUpdate.Counting -> { updateCountdown(state.seconds); return }
                ShutterUpdate.Capture -> { takePhoto(autoShot = true); return }
                ShutterUpdate.Waiting -> updateCountdown(null)
            }
        }
        guidance.text = when {
            framingReady && shutterEligible && autoShutter -> getString(R.string.auto_steady)
            aligned -> "That's the frame. Hold steady and take the photo."
            aiming && !waypoint && !autoZoom -> "Pinch to match the outlined frame."
            aiming && zoomAnimator.moving -> "Framing your shot…"
            else -> framingPrompt()
        }
    }

    private fun framingPrompt(): String = framingInstruction ?: "Move toward the outlined frame."

    private fun updateCountdown(seconds: Int?) {
        if (seconds == null) {
            countdown.visibility = View.GONE
        } else {
            val label = seconds.toString()
            if (countdown.visibility != View.VISIBLE || countdown.text != label) {
                countdown.text = label
                countdown.visibility = View.VISIBLE
                guidance.text = getString(R.string.auto_counting, seconds)
            }
        }
    }
    private fun trackingLost(estimated: List<ScenePoint>? = null) {
        automaticShutter.interrupt(); updateCountdown(null)
        if (verifyingUntil != 0L) {
            if (SystemClock.elapsedRealtime() >= verifyingUntil) {
                modelReason += " Image match timed out."
                cancelGuide()
                retryAutomatic(R.string.recheck_shot)
            }
            return
        }
        if (guideLifetime.expired(SystemClock.elapsedRealtime())) {
            if (automaticGuideAt != 0L) abandonAutomaticGuide() else {
                cancelGuide()
                guidance.setText(R.string.guide_recovering)
            }
            return
        }
        hold.reset(); waypointHold.reset(); explorationHold.reset()
        overlay.aligned = false; overlay.fresh = false
        val age = lastGood?.let { SystemClock.elapsedRealtime() - it.timeMs } ?: Long.MAX_VALUE
        if (age > 300) zoomAnimator.stop()
        if (estimated != null) overlay.frame = estimated else if (age > 600) {
            overlay.frame = null
            overlay.aimPoint = null
        }
        overlay.invalidate()
        val recovering = getString(R.string.guide_recovering)
        if (guidance.text != recovering) guidance.text = recovering
    }
    private fun cancelGuide() {
        generation++
        verifyingUntil = 0
        overlay.targetVisible = true
        framingInstruction = null
        automaticGuideAt = 0
        latestGuideFrameAt = 0
        shutterEligible = false
        exploration = null
        explorationHold.reset()
        automaticShutter.reset(); updateCountdown(null); zoomAnimator.stop()
        environmentDialog?.dismiss(); environmentDialog=null
        environmentRoute.clear(); activeEnvironmentBatch?.close(); activeEnvironmentBatch=null
        environmentSweep?.cancel(); environmentSweep=null; analyzer.endEnvironment()
        sweepRecorder?.cancel(); sweepRecorder = null
        planner.cancel(); analyzer.cancelSnapshot(); tracker.reset()
        analyzer.trackingGuide = false
        scanning = false; activePlan = false; lastGood = null; hold.reset(); waypointHold.reset()
        overlay.frame = null; overlay.aimPoint = null; overlay.aligned = false; overlay.fresh = false
        overlay.targetPreview?.recycle(); overlay.targetPreview = null; overlay.invalidate()
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
        cancelGuide()
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
        cancelGuide()
        guidance.setText(R.string.ready_to_frame)
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
        cancelGuide()
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
                cancelGuide()
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
        cancelGuide()
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
                    scanning = false
                    refreshButtons()
                    guidance.text = if (result.isSuccess) "Test-image request succeeded. Review the scan diagnostics." else "Test-image request failed. Review the scan diagnostics."
                    showReport("Test-image result", (result.exceptionOrNull()?.message ?: "Connectivity and composition-response parsing succeeded.") + "\n\n$lastDiagnostic")
                }
            }
        }
    }
    private fun availablePhotoModes(): List<PhotoMode> {
        val extensions = quality.supported(baseSelector).map { it.first }
        return buildList {
            add(PhotoMode.JPEG)
            if (extensions.isNotEmpty()) add(PhotoMode.ADAPTIVE)
            if (ExtensionMode.AUTO in extensions) add(PhotoMode.AUTO)
            if (ExtensionMode.HDR in extensions) add(PhotoMode.HDR)
            if (ExtensionMode.NIGHT in extensions) add(PhotoMode.NIGHT)
        }
    }

    private fun photoModeLabel(mode: PhotoMode) = getString(when (mode) {
        PhotoMode.JPEG -> R.string.processing_jpeg
        PhotoMode.ADAPTIVE -> R.string.processing_adaptive
        PhotoMode.AUTO -> R.string.processing_auto
        PhotoMode.HDR -> R.string.processing_hdr
        PhotoMode.NIGHT -> R.string.processing_night
    })

    private fun chooseProcessing() {
        val modes = availablePhotoModes()
        AlertDialog.Builder(this).setTitle(R.string.photo_processing)
            .setSingleChoiceItems(modes.map(::photoModeLabel).toTypedArray(), modes.indexOf(photoMode).coerceAtLeast(0)) { dialog, index ->
                photoMode = modes[index]
                cameraPreferences.edit().putString("photo_mode", photoMode.name).apply()
                dialog.dismiss()
                updateCameraStatus()
            }.setNegativeButton(R.string.close, null).show()
    }
    private fun updateCameraStatus() {
        if (!::telemetry.isInitialized || activePlan || scanning) return
        val method = photoModeLabel(photoMode.takeIf { it in availablePhotoModes() } ?: PhotoMode.JPEG)
        telemetry.text = getString(R.string.capture_details, method)
    }

    private fun flipCamera() {
        val camera = controller ?: return
        if (!cameraReady || saving || scanning) return
        val next = if (baseSelector != CameraSelector.DEFAULT_FRONT_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!camera.hasCamera(next)) { toast(R.string.no_other_camera); return }
        clearSubjectTap()
        cancelGuide()
        automaticScan.reset()
        val previous = baseSelector
        try { camera.cameraSelector = next; baseSelector = next; updateCameraStatus() }
        catch (_: Exception) { camera.cameraSelector = previous; toast(R.string.no_other_camera) }
        motion.configure(camera.cameraInfo, baseSelector == CameraSelector.DEFAULT_BACK_CAMERA)
        checkOpeningZoom = BuildConfig.VIVO_DEMO && baseSelector == CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun takePhoto(autoShot: Boolean = false) {
        val camera = controller ?: return
        if (!cameraReady || saving || !cameraForeground() || (autoShot && (!autoShutter || !shutterEligible))) return
        val frameAtShutter = analyzer.liveScene
        val freshSignature = frameAtShutter?.takeIf { SystemClock.elapsedRealtime() - it.timeMs <= 450 }?.signature
        val mode = quality.processing(baseSelector, photoMode, classifyLighting(freshSignature))
        clearSubjectTap()
        cancelGuide()
        saving = true
        guidance.setText(R.string.capturing_photo)
        photoCapture.take(camera, baseSelector, mode,
            ownsCamera = { !isDestroyed && controller === camera },
            canShoot = { controller === camera && cameraForeground() },
            progress = { if (!isDestroyed) guidance.text = it }) { outcome, restored ->
                if (isDestroyed) return@take
                saving = false
                if (!restored && controller === camera) cameraFailed(camera)
                outcome.onSuccess { saved ->
                    lastPhoto = saved.uri; gallery.isEnabled = true
                    lastPhotoDetails = "Last photo: ${saved.width} × ${saved.height} · ${saved.processing}"
                    telemetry.text = lastPhotoDetails
                    toast(R.string.photo_saved)
                    if (restored) waitForAnotherScene(saved = true, reference = frameAtShutter)
                }.onFailure {
                    if (restored) waitForAnotherScene(reference = frameAtShutter, message = getString(R.string.photo_failed))
                    toast(R.string.photo_failed)
                }
            }
    }
    private fun openLastPhoto() {
        if (saving) return
        cancelGuide()
        lastPhoto?.let { uri -> runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { toast(R.string.no_gallery) } }
    }
    private fun refreshButtons() {
        renderControls(interaction.controls)
    }
    private fun renderControls(controls: CameraControls) {
        if (!::automatic.isInitialized) return
        capture.isEnabled = controls.captureEnabled
        gallery.isEnabled = !saving && lastPhoto != null
        flip.isEnabled = controls.flipEnabled
        automatic.isEnabled = cameraReady && !saving
        if (::findShot.isInitialized) findShot.isEnabled = cameraReady && !saving && !scanning
        automatic.setText(if (autoShutter) R.string.auto_on else R.string.auto_off)
        cancel.visibility = if (controls.cancelVisible) View.VISIBLE else View.GONE
    }
    private fun toast(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_SHORT).show()
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("lastPhoto", lastPhoto?.toString()); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        zoomAnimator.stop()
        environmentRoute.clear(); activeEnvironmentBatch?.close(); activeEnvironmentBatch=null
        environmentSweep?.cancel(); environmentDialog?.dismiss(); analyzer.endEnvironment()
        generation++; handler.removeCallbacksAndMessages(null); analyzer.cancelSnapshot()
        controller?.clearImageAnalysisAnalyzer(); controller?.unbind(); planner.cancel()
        analysisExecutor.execute { tracker.close(); analyzer.closeEnvironment() }; analysisExecutor.shutdown()
        modelExecutor.execute {
            planner.close(); localScorer.close(); softwareDepth.close(); subjectDetector.close(); sceneRecognition.close()
            if (BuildConfig.VIVO_DEMO) contextualScorer.close()
        }; modelExecutor.shutdown()
        super.onDestroy()
    }
}
