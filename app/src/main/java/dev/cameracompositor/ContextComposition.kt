package dev.cameracompositor

import kotlin.math.hypot

/** Turns one observed view into an automatic guide. Learned ranking is optional. */
class ContextComposition(private val recognition: SceneRecognition, private val detector: SceneSubjectDetector,
                         private val scorer: LocalResearchScorer?) {
    fun evaluate(batch: EnvironmentBatch, maxRelativeZoom: Float, tap: SubjectTap?,
                 allowExploration: Boolean, expected: SubjectHint?, isActive: () -> Boolean): LocalComposition.Result {
        val current = batch.frames.last()
        val view = current.view
        val picked = tap?.inBatch(batch)?.pointIn(batch.map, view.id)
        fun observe(message: String, recheck: Boolean = false) =
            LocalComposition.Result(EnvironmentDecision.ObserveMore(message), emptyMap(), false, recheckAfterSettling = recheck)
        if (tap != null && picked == null) return observe("Keep the selected subject in view, or tap it again.")
        val image = current.frame.image
        val layout = sceneLayout(image)
        if (!layout.hasSceneDetail && picked == null) {
            return observe(if (layout.brightness < .15f) "Aim toward a better-lit part of the scene."
                else "Include an edge, object, or doorway.", recheck = true)
        }
        if (!isActive()) return observe("Checking the view again…")
        val detections = detector.detectAll(image)
        val detection = SubjectDetection.mainSubject(detections, picked)
        val subjects = ContextShots.observedSubjects(layout, recognition.scores(image), detections)
        val target = when {
            picked != null -> selectedSubject(picked, layout, subjects, detection)
                ?: return observe("Tap an area with a visible edge or detail.")
            expected != null -> subjects.filter { it.kind == expected.kind && it.strength >= .18f }
                .minByOrNull { hypot(it.point.x - expected.point.x, it.point.y - expected.point.y) }
                ?.takeIf { hypot(it.point.x - expected.point.x, it.point.y - expected.point.y) <= .31f }
                ?: return observe("Bring the subject back into view.")
            else -> null
        }
        val evidence = ShotEvidence(view, layout, if (target == null) subjects else listOf(target) + subjects.filterNot { it == target }, detections)
        val shots = AdaptiveFraming.candidates(batch.map, evidence, maxRelativeZoom, target)
        if (!isActive()) return observe("Checking the view again…")
        val scores = scorer?.takeIf { shots.size > 1 }?.let { model ->
            runCatching {
                if (model.available) model.score(image, shots.map { it.comparisonBox }, isActive) else null
            }
        }
        val report = scores?.exceptionOrNull()?.javaClass?.simpleName
        val choice = AdaptiveFraming.choose(shots, scores?.getOrNull())
        if (choice != null) {
            val selected = choice.shot.subject?.kind
            val label = if (choice.shot.subject?.paired == true) "pair"
                else if (choice.shot.subject?.outlinedDetail == true) "outlined detail"
                else choice.shot.cue?.title ?: selected?.description ?: "Selected subject"
            val reason = choice.shot.cue?.instruction ?: "Follow the outline to frame the $label."
            val guide = EnvironmentDecision.Guide(view.id, choice.shot.crop, label, reason)
            return LocalComposition.Result(guide, emptyMap(), choice.canAutoCapture, selected, shots.size, report)
        }
        val main = target ?: subjects.firstOrNull { it.kind != SceneKind.WALKWAY && it.strength >= .26f }
        val edge = main?.framingBounds
        val clipped = edge != null && minOf(edge.left, edge.top, 1 - edge.right, 1 - edge.bottom) <= .04f
        val more = EnvironmentDecision.ObserveMore("Widen the view to include more of the subject.")
        val next = if (allowExploration && clipped) NextView.suggest(view.id, CompositionStyle.WIDE, null, main, more) else null
        val decision = next ?: if (clipped) more else EnvironmentDecision.Keep("Keep this view. Take the photo when ready.")
        return LocalComposition.Result(decision, emptyMap(), false, main?.kind, shots.size, report)
    }

    private fun selectedSubject(point: ScenePoint, layout: SceneLayout, subjects: List<SceneSubject>,
                                detection: DetectedSubject?): SceneSubject? {
        if (detection != null) return ContextShots.detectedSubject(detection)
        subjects.firstOrNull { subject ->
            val box = subject.framingBounds
            subject.strength >= .18f && point.x in box.left..box.right && point.y in box.top..box.bottom
        }?.let { return it }
        val box = SubjectBox((point.x - .11f).coerceAtLeast(0f), (point.y - .14f).coerceAtLeast(0f),
            (point.x + .11f).coerceAtMost(1f), (point.y + .14f).coerceAtMost(1f))
        return if (layout.subjectDetail(box) >= .020f) SceneSubject(SceneKind.OBJECTS, box, point, .22f) else null
    }
}
