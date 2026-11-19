package dev.cameracompositor

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.hypot

/** Private prototype planner, with one inference batch against the current wide camera image. */
class ContextComposition(private val recognition: SceneRecognition, private val detector: SceneSubjectDetector,
                         private val scorer: LocalResearchScorer) {
    fun evaluate(batch: EnvironmentBatch, maxRelativeZoom: Float, style: CompositionStyle,
                 allowExploration: Boolean, expected: SubjectHint?, isActive: () -> Boolean): LocalComposition.Result? {
        if (!scorer.available) return null
        val current = batch.frames.last()
        val viewId = current.view.id
        val start = SystemClock.elapsedRealtime()
        if (!isActive()) return null
        val latest = evidence(current, detect = true)
        val views = mutableListOf(latest)
        val center = ScenePoint(.5f, .5f)
        for (frame in batch.frames.dropLast(1).asReversed()) {
            if (views.size == 3 || !isActive() || SystemClock.elapsedRealtime() - start > 740) break
            val projected = batch.map.projectPoints(frame.view.id, viewId, listOf(center))?.singleOrNull() ?: continue
            if (hypot(projected.x - .5f, projected.y - .5f) !in .065f.. .36f) continue
            if (views.drop(1).any { old ->
                    val oldCenter = batch.map.projectPoints(old.view.id, viewId, listOf(center))?.singleOrNull()
                    oldCenter != null && hypot(oldCenter.x - projected.x, oldCenter.y - projected.y) < .06f
                }) continue
            views.add(evidence(frame))
        }
        if (!isActive()) return null
        val shots = ContextShots.candidates(batch.map, views, style, maxRelativeZoom, expected)
        val ranked = if (shots.size > 1) ContextShots.choose(shots,
            scorer.score(current.frame.image, shots.map { it.comparisonBox }, isActive)) else null
        val subject = ranked?.shot?.subject
        if (ranked != null && subject != null) {
            val shot = EnvironmentDecision.Guide(ranked.shot.viewId, ranked.shot.crop, subject.kind.description,
                "The framing gives the ${subject.kind.description} more room to stand out.")
            return LocalComposition.Result(shot, emptyMap(), ranked.gain >= .23f && subject.strength >= .21f,
                subject.kind, shots.size)
        }
        val observed = EnvironmentDecision.ObserveMore("No clear shot yet. Aim at a subject or tap Find shot.")
        val main = latest.subjects.firstOrNull { it.kind != SceneKind.WALKWAY && it.strength >= .21f }
        val edge = main?.framingBounds
        val clipped = edge != null && minOf(edge.left, edge.top, 1 - edge.right, 1 - edge.bottom) <= .055f
        val next = if (allowExploration && clipped) NextView.suggest(viewId, style, null, main, observed) else null
        val keep = if (main != null && !clipped) EnvironmentDecision.Keep("This framing works. Take the photo when ready.") else observed
        return LocalComposition.Result(next ?: keep, emptyMap(), false, main?.kind, shots.size)
    }

    private fun evidence(entry: EnvironmentFrame, detect: Boolean = false): ShotEvidence {
        val image = entry.frame.image
        val rgb = IntArray(SceneLayout.WIDTH * SceneLayout.HEIGHT)
        val reduced = Bitmap.createScaledBitmap(image, SceneLayout.WIDTH, SceneLayout.HEIGHT, true)
        try {
            reduced.getPixels(rgb, 0, SceneLayout.WIDTH, 0, 0, SceneLayout.WIDTH, SceneLayout.HEIGHT)
        } finally {
            if (reduced !== image) reduced.recycle()
        }
        val layout = SceneLayout(rgb, null)
        val scores = recognition.scores(image)
        val detected = if (detect) detector.detect(image) else null
        val subjects = ContextShots.subjects(layout, scores, detected)
        return ShotEvidence(entry.view, layout, subjects)
    }
}
