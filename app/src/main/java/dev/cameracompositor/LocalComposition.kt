package dev.cameracompositor

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot

data class SubjectTap(val point: ScenePoint, val view: LiveScene, val zoom: Float) {
    fun inBatch(batch: EnvironmentBatch): SelectedSubject? {
        val match = batch.frames.firstOrNull { (observation, frame) ->
            abs(observation.zoom / zoom - 1f) < .022f &&
                FrameSignature.sameSelectedView(view.signature, FrameSignature.sample(frame.gray, frame.width, frame.height))
        } ?: return null
        return SelectedSubject(point, match.view.id)
    }
}

/** Works with a conventional CameraX stream. Depth is optional per view. */
class LocalComposition(private val depth: SoftwareDepth, private val detector: SceneSubjectDetector,
                       private val recognition: SceneRecognition) {
    data class Result(val decision: EnvironmentDecision, val relativeDepth: Map<Int, RelativeDepth>,
                      val canAutoCapture: Boolean, val recognized: SceneKind? = null)

    fun evaluate(batch: EnvironmentBatch, remainingZoom: Float, style: CompositionStyle, tap: SubjectTap?,
                 allowExploration: Boolean, expected: SubjectHint?, isActive: () -> Boolean): Result {
        val start = SystemClock.elapsedRealtime()
        val eligible = batch.frames.filter { batch.map.routeTo(it.view.id) != null }.associateBy { it.view.id }
        val latest = batch.frames.last().view.id
        val selectedSubject = tap?.inBatch(batch)
        val picked = selectedSubject?.pointIn(batch.map, latest)
        if (tap != null && picked == null) {
            return Result(EnvironmentDecision.ObserveMore("The selected subject moved out of view. Tap it again."), emptyMap(), false)
        }
        val subject = if (isActive()) detector.detect(batch.frames.last().frame.image, picked) else null
        val center = SubjectBox(.49f, .49f, .51f, .51f)
        val selected = batch.map.plannerViews().asReversed().filter { view ->
            if (view.id !in eligible) false
            else if (view.id == latest) true
            else batch.map.project(view.id, latest, center)?.let {
                hypot((it.left + it.right) / 2 - .5f, (it.top + it.bottom) / 2 - .5f) >= .033f
            } == true
        }
        val estimates = mutableMapOf<Int, RelativeDepth>()
        for (view in selected.takeIf { it.size >= 2 }.orEmpty()) {
            if (!isActive() || SystemClock.elapsedRealtime() - start > 950) break
            val frame = eligible.getValue(view.id).frame
            depth.estimate(frame.image)?.let { estimates[view.id] = it }
        }
        val layers = DepthAgreement.corroborated(batch.map, estimates)
        val layouts = eligible.mapValues { (id, entry) ->
            if (!isActive()) throw SceneServiceException("cancelled", "Scan cancelled.")
            val pixels = IntArray(SceneLayout.WIDTH * SceneLayout.HEIGHT)
            val image = entry.frame.image
            val reduced = Bitmap.createScaledBitmap(image, SceneLayout.WIDTH, SceneLayout.HEIGHT, true)
            try {
                reduced.getPixels(pixels, 0, SceneLayout.WIDTH, 0, 0, SceneLayout.WIDTH, SceneLayout.HEIGHT)
            } finally {
                if (reduced !== image) reduced.recycle()
            }
            SceneLayout(pixels, layers[id])
        }
        val scores = if (isActive() && SceneDiscovery.needsSemantics(subject, picked, expected)) {
            recognition.scores(batch.frames.last().frame.image)
        } else null
        val assessment = SceneDiscovery.choose(style, batch.map, layouts, remainingZoom, subject, selectedSubject,
            scores, allowExploration, expected)
        return Result(assessment.plan.decision, layers, assessment.plan.canAutoCapture, assessment.region?.kind)
    }
}

/** Select a reachable observed viewpoint, with optional framing refinement. */
object ObservedComposition {
    private val full = SubjectBox(0f, 0f, 1f, 1f)
    private data class Option(val viewId: Int, val crop: SubjectBox, val improvement: Float, val foregroundRemoved: Float)

    fun choose(map: EnvironmentMap, layouts: Map<Int, SceneLayout>, remainingZoom: Float,
               subject: DetectedSubject? = null, protectedBounds: SubjectBox? = null): EnvironmentDecision {
        val current = map.views.last()
        val starting = layouts.getValue(current.id)
        val focus = starting.focus
        if (focus == null || !starting.hasSubject) {
            return EnvironmentDecision.ObserveMore("Point at a subject with some visible detail.")
        }
        val protected = protectedBounds ?: subject?.takeIf { it.confidence >= .52f }?.box
        val currentWithDepth = starting.rate(full, useDepth = true)
        val currentRgb = starting.rate(full, useDepth = false)
        val startingDetail = starting.subjectDetail(focus)
        var best: Option? = null
        for (view in map.views.asReversed()) {
            val layout = layouts[view.id] ?: continue
            if (map.routeTo(view.id) == null || !layout.hasSubject) continue
            val visibleDetail = if (view.id == current.id) focus else map.project(current.id, view.id, focus) ?: continue
            if (coverage(full, visibleDetail) < .87f || layout.subjectDetail(visibleDetail) < startingDetail * .55f) continue
            val visibleSubject = protected?.let { map.project(current.id, view.id, it) }
            if (protected != null && (visibleSubject == null || coverage(full, visibleSubject) < .97f)) continue
            val comparedDepth = starting.depth != null && layout.depth != null
            val base = if (comparedDepth) currentWithDepth else currentRgb
            val movedCenter = map.project(view.id, current.id, full)?.let { center(it) }
            val measuredChange = movedCenter != null && hypot(movedCenter.x - .5f, movedCenter.y - .5f) > .045f
            if (view.id != current.id && !measuredChange) continue
            val zoomLimit = minOf(remainingZoom, view.maxRelativeZoom, 2.2f)
            val proposals = boxes(zoomLimit, visibleDetail)
            for (box in proposals) {
                val crop = box != full
                if (coverage(box, visibleDetail) < .90f) continue
                if (crop && visibleSubject != null && !hasRoom(box, visibleSubject, .024f)) continue
                val rating = layout.rate(box, comparedDepth)
                val minimumRetained = if (comparedDepth) .56f else .70f
                if (crop && rating.retained < minimumRetained) continue
                val foregroundRemoved = base.foreground - rating.foreground
                val zoomCost = if (crop) .04f + .10f * (1 - (box.right - box.left)) else 0f
                val gain = rating.quality - base.quality - zoomCost
                val minimumGain = if (crop) .030f else .045f
                if (gain >= minimumGain && (best == null || gain > best.improvement + .008f)) {
                    best = Option(view.id, box, gain, foregroundRemoved)
                }
            }
        }
        if (best == null) return EnvironmentDecision.Keep("Keep the current view or show another angle.")
        val label = if (best.viewId == current.id) "Suggested framing" else "Suggested angle"
        val explanation = if (best.foregroundRemoved >= .08f) "Less foreground clutter, with the main detail still in frame."
            else if (best.viewId != current.id) "This observed angle gives the visible detail more room."
            else "This framing gives the main detail more space in the photo."
        return EnvironmentDecision.Guide(best.viewId, best.crop, label, explanation)
    }

    // A "keep" with positive subject evidence can be used by the armed shutter.
    fun currentSubjectReady(layout: SceneLayout, subject: DetectedSubject?): Boolean {
        if (subject == null || subject.confidence < .54f || !hasRoom(full, subject.box, .07f)) return false
        return layout.subjectDetail(subject.box) >= .023f && subjectFit(full, subject.box, subject.category) >= .76f
    }

    private fun subjectFit(frame: SubjectBox, subject: SubjectBox, category: Int): Float {
        val width = frame.right - frame.left
        val height = frame.bottom - frame.top
        val subjectCenter = center(subject)
        val x = (subjectCenter.x - frame.left) / width
        val y = (subjectCenter.y - frame.top) / height
        val extent = maxOf((subject.right - subject.left) / width, (subject.bottom - subject.top) / height)
        val goal = if (category == 0 || category in 14..23) .66f else .53f
        val horizontalError = minOf(kotlin.math.abs(x - .5f), kotlin.math.abs(x - .34f) + .03f,
            kotlin.math.abs(x - .66f) + .03f)
        return (1f - 1.85f * kotlin.math.abs(extent - goal) - horizontalError - .65f * kotlin.math.abs(y - .49f))
            .coerceIn(0f, 1f)
    }

    private fun hasRoom(frame: SubjectBox, subject: SubjectBox, fraction: Float): Boolean {
        val horizontal = (frame.right - frame.left) * fraction
        val vertical = (frame.bottom - frame.top) * fraction
        return subject.left >= frame.left + horizontal && subject.right <= frame.right - horizontal &&
            subject.top >= frame.top + vertical && subject.bottom <= frame.bottom - vertical
    }

    private fun boxes(maxZoom: Float, subject: SubjectBox): List<SubjectBox> = buildList {
        add(full)
        val center = center(subject)
        for (zoom in listOf(1.25f, 1.45f, 1.65f, 1.85f, 2.05f, 2.2f)) {
            if (zoom > maxZoom + .001f) break
            val half = .5f / zoom
            // Sample around the observed subject, with enough room for surrounding context.
            for (dx in listOf(-.14f, -.07f, 0f, .07f, .14f)) for (dy in listOf(-.14f, -.07f, 0f, .07f, .14f)) {
                val x = (center.x + dx).coerceIn(half, 1 - half)
                val y = (center.y + dy).coerceIn(half, 1 - half)
                add(SubjectBox(x - half, y - half, x + half, y + half))
            }
        }
    }.distinct()

    private fun center(box: SubjectBox) = ScenePoint((box.left + box.right) / 2, (box.top + box.bottom) / 2)

    private fun coverage(frame: SubjectBox, subject: SubjectBox): Float {
        val width = subject.right - subject.left
        val height = subject.bottom - subject.top
        if (width < .015f || height < .015f) {
            val center = center(subject)
            return if (center.x in frame.left..frame.right && center.y in frame.top..frame.bottom) 1f else 0f
        }
        val horizontally = (minOf(frame.right, subject.right) - maxOf(frame.left, subject.left)).coerceAtLeast(0f)
        val vertically = (minOf(frame.bottom, subject.bottom) - maxOf(frame.top, subject.top)).coerceAtLeast(0f)
        return horizontally * vertically / (width * height)
    }
}
