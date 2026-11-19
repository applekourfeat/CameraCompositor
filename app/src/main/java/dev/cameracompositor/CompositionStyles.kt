package dev.cameracompositor

import kotlin.math.abs
import kotlin.math.hypot

enum class CompositionStyle { THIRDS, CENTERED, NEGATIVE_SPACE, WIDE }

data class CompositionTarget(val bounds: SubjectBox, val point: ScenePoint, val isPerson: Boolean,
                             val reliable: Boolean, val maxZoom: Float)

data class StyleAssessment(val decision: EnvironmentDecision, val canAutoCapture: Boolean)

/** Map the photographer's point through the same observations used to guide the live camera. */
data class SelectedSubject(val point: ScenePoint, val viewId: Int) {
    fun pointIn(map: EnvironmentMap, viewId: Int): ScenePoint? = map.projectPoints(this.viewId, viewId, listOf(point))
        ?.singleOrNull()?.takeIf { it.x in 0f..1f && it.y in 0f..1f }
}

object StyledComposition {
    private val full = SubjectBox(0f, 0f, 1f, 1f)
    private val zooms = listOf(1.10f, 1.20f, 1.30f, 1.42f, 1.56f, 1.72f, 1.90f, 2.05f)
    private data class Fit(val error: Float, val quiet: Float, val extent: Float)
    private data class Option(val view: Int, val box: SubjectBox, val score: Float)

    fun choose(style: CompositionStyle, map: EnvironmentMap, layouts: Map<Int, SceneLayout>,
               remainingZoom: Float, detection: DetectedSubject?, selected: SelectedSubject? = null,
               semantic: SceneSubject? = null): StyleAssessment {
        val current = map.views.last()
        val initial = layouts.getValue(current.id)
        val picked = selected?.pointIn(map, current.id)
        if (selected != null && picked == null) return more("Keep the selected subject in view, or tap it again.")
        val target = target(initial, detection, picked, semantic)
        if (picked != null && target == null) return more("Tap a subject with visible detail.")
        if (style == CompositionStyle.WIDE) return wide(map, layouts, remainingZoom, detection, target)
        if (target == null) return more("Point toward a subject with a clear outline.")

        val initialFit = fit(style, initial, full, target)
        val initialFits = acceptable(style, initialFit) && contains(full, target.bounds, .03f)
        val referenceDetail = initial.subjectDetail(target.bounds)
        var best: Option? = null
        for (view in map.views.asReversed()) {
            val layout = layouts[view.id] ?: continue
            if (map.routeTo(view.id) == null) continue
            val visible = project(map, current.id, view.id, target) ?: continue
            if (view.id != current.id) {
                val moved = hypot(visible.point.x - target.point.x, visible.point.y - target.point.y)
                if (moved < .035f || layout.subjectDetail(visible.bounds) < referenceDetail * .50f) continue
            }
            val maxZoom = minOf(view.maxRelativeZoom, remainingZoom, visible.maxZoom,
                if (style == CompositionStyle.NEGATIVE_SPACE) 1.56f else 2.05f)
            val depthAvailable = initial.depth != null && layout.depth != null
            val baseline = initial.rate(full, depthAvailable)
            val baseScore = score(style, initialFit, baseline, 1f)
            for (box in proposals(style, visible, maxZoom)) {
                if (view.id == current.id && box == full) continue
                if (!contains(box, visible.bounds, .038f)) continue
                val fit = fit(style, layout, box, visible)
                if (!acceptable(style, fit)) continue
                val rating = layout.rate(box, depthAvailable)
                if (rating.foreground > baseline.foreground + .09f || rating.clipped > baseline.clipped + .075f ||
                    rating.boundary > baseline.boundary + .17f) continue
                val zoom = 1f / (box.right - box.left)
                val gain = score(style, fit, rating, zoom) - baseScore
                val minimum = if (initialFits) .095f else .043f
                if (gain >= minimum && (best == null || gain > best.score + .009f)) best = Option(view.id, box, gain)
            }
        }
        if (best != null) {
            val (label, reason) = when (style) {
                CompositionStyle.THIRDS -> "Thirds" to "Places the subject near a grid intersection and keeps room around it."
                CompositionStyle.CENTERED -> "Centered" to "Brings the subject to the center with room around it."
                CompositionStyle.NEGATIVE_SPACE -> "Negative space" to "Keeps the subject to one side and leaves a quieter area beside it."
                CompositionStyle.WIDE -> error("Wide scene is planned separately")
            }
            return StyleAssessment(EnvironmentDecision.Guide(best.view, best.box, label, reason), target.reliable)
        }
        if (initialFits) {
            return StyleAssessment(EnvironmentDecision.Keep("This view fits the selected style."), target.reliable)
        }
        return more(when (style) {
            CompositionStyle.THIRDS -> "Show more room around the subject, especially to one side."
            CompositionStyle.CENTERED -> "Keep the whole subject visible and point closer to its center."
            CompositionStyle.NEGATIVE_SPACE -> "Show a quieter area beside the subject."
            CompositionStyle.WIDE -> "Show more of the scene."
        })
    }

    private fun wide(map: EnvironmentMap, layouts: Map<Int, SceneLayout>, zoom: Float,
                     detection: DetectedSubject?, target: CompositionTarget?): StyleAssessment {
        val decision = ObservedComposition.choose(map, layouts, minOf(zoom, 1.25f), detection, target?.bounds)
        return when (decision) {
            is EnvironmentDecision.Guide -> StyleAssessment(decision.copy(subject = "Wide scene"), target?.reliable == true)
            is EnvironmentDecision.Keep -> StyleAssessment(EnvironmentDecision.Keep("This wider view keeps the surroundings."),
                ObservedComposition.currentSubjectReady(layouts.getValue(map.views.last().id), detection))
            is EnvironmentDecision.ObserveMore -> more(decision.reason)
            is EnvironmentDecision.Explore -> StyleAssessment(decision, false)
        }
    }

    private fun target(layout: SceneLayout, detection: DetectedSubject?, picked: ScenePoint?, semantic: SceneSubject?): CompositionTarget? {
        val detected = detection?.takeIf { it.confidence >= .46f && (picked == null || contains(it.box, picked)) }
        if (detected != null && (picked != null || semantic == null || detected.confidence >= .58f)) {
            val b = detected.box
            val person = detected.category == 0 && (b.bottom - b.top) > (b.right - b.left) * 1.12f
            val y = if (person) b.top + (b.bottom - b.top) * .29f else (b.top + b.bottom) * .5f
            return CompositionTarget(b, ScenePoint((b.left + b.right) * .5f, y), person,
                picked != null || detected.confidence >= .54f, 2.05f)
        }
        if (picked != null) {
            val box = SubjectBox((picked.x - .13f).coerceAtLeast(0f), (picked.y - .16f).coerceAtLeast(0f),
                (picked.x + .13f).coerceAtMost(1f), (picked.y + .16f).coerceAtMost(1f))
            if (layout.subjectDetail(box) < .010f) return null
            return CompositionTarget(box, picked, false, true, 1.56f)
        }
        if (semantic != null) {
            return CompositionTarget(semantic.framingBounds, semantic.point, semantic.kind == SceneKind.PERSON,
                semantic.strength >= .19f, 1.75f)
        }
        val box = layout.isolatedFocus() ?: return null
        return CompositionTarget(box, ScenePoint((box.left + box.right) * .5f, (box.top + box.bottom) * .5f), false, false, 1.42f)
    }

    private fun project(map: EnvironmentMap, from: Int, to: Int, target: CompositionTarget): CompositionTarget? {
        if (from == to) return target
        val box = map.project(from, to, target.bounds) ?: return null
        val point = SelectedSubject(target.point, from).pointIn(map, to) ?: return null
        return target.copy(bounds = box, point = point)
    }

    private fun placements(style: CompositionStyle, person: Boolean): List<ScenePoint> = when (style) {
        CompositionStyle.THIRDS -> if (person) listOf(ScenePoint(1f / 3, 1f / 3), ScenePoint(2f / 3, 1f / 3))
            else listOf(ScenePoint(1f / 3, 1f / 3), ScenePoint(2f / 3, 1f / 3), ScenePoint(1f / 3, 2f / 3), ScenePoint(2f / 3, 2f / 3))
        CompositionStyle.CENTERED -> listOf(ScenePoint(.5f, if (person) .385f else .5f))
        CompositionStyle.NEGATIVE_SPACE -> listOf(ScenePoint(.27f, if (person) .35f else .51f), ScenePoint(.73f, if (person) .35f else .51f))
        CompositionStyle.WIDE -> emptyList()
    }

    private fun proposals(style: CompositionStyle, target: CompositionTarget, maxZoom: Float): List<SubjectBox> = buildList {
        add(full)
        for (zoom in (zooms.filter { it <= maxZoom } + maxZoom).distinct()) {
            if (zoom <= 1.035f) continue
            val size = 1f / zoom
            for (placement in placements(style, target.isPerson)) {
                val left = (target.point.x - placement.x * size).coerceIn(0f, 1f - size)
                val top = (target.point.y - placement.y * size).coerceIn(0f, 1f - size)
                add(SubjectBox(left, top, left + size, top + size))
            }
        }
    }.distinct()

    private fun fit(style: CompositionStyle, layout: SceneLayout, frame: SubjectBox, target: CompositionTarget): Fit {
        val width = frame.right - frame.left
        val height = frame.bottom - frame.top
        val x = (target.point.x - frame.left) / width
        val y = (target.point.y - frame.top) / height
        val error = placements(style, target.isPerson).minOf { hypot(x - it.x, y - it.y) }
        val quiet = if (style == CompositionStyle.NEGATIVE_SPACE) layout.quietSide(frame, target.bounds, x < .5f) else 0f
        val extent = maxOf((target.bounds.right - target.bounds.left) / width,
            (target.bounds.bottom - target.bounds.top) / height)
        return Fit(error, quiet, extent)
    }

    private fun acceptable(style: CompositionStyle, fit: Fit): Boolean = when (style) {
        CompositionStyle.NEGATIVE_SPACE -> fit.error <= .138f && fit.quiet >= .64f && fit.extent in .12f .. .75f
        CompositionStyle.CENTERED -> fit.error <= .095f && fit.extent in .18f .. .92f
        CompositionStyle.THIRDS -> fit.error <= .115f && fit.extent in .15f .. .90f
        CompositionStyle.WIDE -> false
    }

    private fun score(style: CompositionStyle, fit: Fit, rating: SceneLayout.Rating, zoom: Float): Float {
        val wanted = if (style == CompositionStyle.NEGATIVE_SPACE) .38f else .55f
        val sizePenalty = (wanted - fit.extent).coerceAtLeast(0f) * .21f + (fit.extent - .73f).coerceAtLeast(0f) * .42f
        return -1.55f * fit.error - sizePenalty + .21f * fit.quiet - .16f * (zoom - 1) -
            .45f * rating.foreground - .35f * rating.clipped - .23f * rating.boundary
    }

    private fun contains(box: SubjectBox, point: ScenePoint) = point.x in box.left..box.right && point.y in box.top..box.bottom

    private fun contains(frame: SubjectBox, subject: SubjectBox, margin: Float): Boolean {
        val x = (frame.right - frame.left) * margin
        val y = (frame.bottom - frame.top) * margin
        return subject.left >= frame.left + x && subject.right <= frame.right - x &&
            subject.top >= frame.top + y && subject.bottom <= frame.bottom - y
    }

    private fun more(reason: String) = StyleAssessment(EnvironmentDecision.ObserveMore(reason), false)
}
