package dev.cameracompositor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

data class ShotEvidence(val view: EnvironmentView, val layout: SceneLayout, val subjects: List<SceneSubject>)
data class ContextShot(val viewId: Int, val crop: SubjectBox, val comparisonBox: SubjectBox,
                       val subject: SceneSubject?, val framingGain: Float, val cue: LayoutCue? = null)
data class RankedShot(val shot: ContextShot, val gain: Float, val photoScore: Float)

/** Candidate frames can come from several observations. They compete in one captured view. */
object ContextShots {
    private val full = SubjectBox(0f, 0f, 1f, 1f)
    private val sizes = listOf(.78f, .70f, .62f, .55f, .48f, .42f, .36f)
    private val middle = ScenePoint(.5f, .44f)
    private const val MIN_IMPROVEMENT = .18f

    fun subjects(layout: SceneLayout, scores: FloatArray?, detection: DetectedSubject?): List<SceneSubject> {
        val semantic = scores?.let { SceneRegions.subjects(it, layout, includeBuildingOutlines = true) }.orEmpty()
        val found = detection?.takeIf { it.confidence >= .58f } ?: return semantic
        val subject = detectedSubject(found)
        return listOf(subject) + semantic.filterNot { it.kind == subject.kind && distance(it.point, subject.point) < .20f }
    }

    internal fun detectedSubject(found: DetectedSubject): SceneSubject {
        val kind = when (found.category) {
            0 -> SceneKind.PERSON
            in 14..23 -> SceneKind.ANIMAL
            else -> SceneKind.OBJECTS
        }
        val b = found.box
        val point = ScenePoint((b.left + b.right) / 2, b.top + (b.bottom - b.top) *
            if (kind == SceneKind.PERSON) .35f else .5f)
        return SceneSubject(kind, b, point, .20f + .19f * found.confidence, detected = true)
    }

    fun candidates(map: EnvironmentMap, evidence: List<ShotEvidence>, style: CompositionStyle?,
                   maxRelativeZoom: Float, expected: SubjectHint? = null,
                   requiredSubject: SceneSubject? = null): List<ContextShot> {
        val current = evidence.first()
        val context = current.view.id
        val original = ContextShot(context, full, full, null, 0f)
        val baseHighlights = current.layout.rate(full, useDepth = false).clipped
        val options = mutableListOf<ContextShot>()
        for ((view, layout, subjects) in evidence) {
            val qualityLimit = if (style == null) maxOf(1.17f, 1.85f / view.zoom) else 1.85f / view.zoom
            val zoomLimit = minOf(view.maxRelativeZoom, maxRelativeZoom, qualityLimit,
                if (style == CompositionStyle.WIDE) 1.32f else 3.2f)
            val distinctSubjects = subjects.filter { it.strength >= .187f && it.kind != SceneKind.WALKWAY }
                .filter { requiredSubject == null || it == requiredSubject }
                .distinctBy { (it.point.x * 8).toInt() to (it.point.y * 8).toInt() }
                .take(4)
            for (subject in distinctSubjects) {
                val projected = map.projectPoints(view.id, context, listOf(subject.point))?.singleOrNull() ?: continue
                if (expected != null && (subject.kind != expected.kind || distance(projected, expected.point) > .31f)) continue
                val baseline = if (view.id == context) subject else current.subjects
                    .filter { it.kind == subject.kind && distance(it.point, projected) < .20f }
                    .minByOrNull { distance(it.point, projected) } ?: continue
                val placements = placements(style, layout, subject)
                val baselineQuality = framing(current.layout, full, baseline, current.subjects, style, placements)
                val allowedSizes = if (style == null && zoomLimit < 1.40f) listOf(.91f, .85f) + sizes else sizes
                val proposals = allowedSizes.filter { 1 / it <= zoomLimit + .001f }.flatMap { size ->
                    placements.map { position -> boxAt(subject.point, position, size) }
                }.distinct()
                val ranking = proposals.mapNotNull { crop ->
                    if (!containsSubject(crop, subject.framingBounds)) return@mapNotNull null
                    val compared = inContext(map, view.id, context, crop) ?: return@mapNotNull null
                    if (layout.rate(crop, useDepth = false).clipped - baseHighlights > .11f) return@mapNotNull null
                    val desired = framing(layout, crop, subject, distinctSubjects, style, placements)
                    val gain = desired - baselineQuality - .11f * ln(1f / width(crop))
                    ContextShot(view.id, crop, compared, subject, gain)
                }.sortedByDescending { it.framingGain }
                options.addAll(ranking.take(8))
            }
        }
        val result = mutableListOf(original)
        for (option in options.sortedByDescending { it.framingGain }) {
            if (result.none { overlap(it.comparisonBox, option.comparisonBox) > .91f }) {
                result.add(option)
            }
            if (result.size == 48) {
                break
            }
        }
        return result
    }

    fun choose(shots: List<ContextShot>, scores: FloatArray): RankedShot? {
        require(shots.isNotEmpty() && shots.size == scores.size && scores.all(Float::isFinite))
        val first = scores.first()
        return shots.indices.drop(1).map { index ->
            // The learned model compares visual balance. Subject emphasis reflects the chosen shooting style.
            RankedShot(shots[index], .40f * (scores[index] - first) + shots[index].framingGain, scores[index])
        }.filter { it.gain >= MIN_IMPROVEMENT && it.photoScore >= 2.5f && it.photoScore >= first - 1.26f }
            .maxByOrNull { it.gain }
    }

    private fun framing(layout: SceneLayout, crop: SubjectBox, subject: SceneSubject,
                        alternatives: List<SceneSubject>, style: CompositionStyle?, placements: List<ScenePoint>): Float {
        val core = subject.framingBounds
        val size = width(crop)
        val extent = maxOf(width(core), core.bottom - core.top) / size
        val p = ScenePoint((subject.point.x - crop.left) / size, (subject.point.y - crop.top) / size)
        val attention = when (style) {
            CompositionStyle.WIDE -> -.55f * (extent - .43f).coerceAtLeast(0f)
            CompositionStyle.NEGATIVE_SPACE -> -.95f * abs(extent - .38f)
            else -> -1.80f * (.57f - extent).coerceAtLeast(0f) - 1.38f * (extent - .76f).coerceAtLeast(0f)
        }
        val competing = alternatives.asSequence().filter { it != subject && it.strength >= subject.strength * .66f }
            .filter { distance(it.point, subject.point) > .20f && overlap(it.framingBounds, core) < .20f }
            .maxOfOrNull { other -> coverage(crop, other.framingBounds).coerceIn(0f, 1f) *
                (other.strength / subject.strength).coerceAtMost(1f) } ?: 0f
        val styleError = placements.minOf { distance(it, p) }
        val quiet = when {
            style == CompositionStyle.NEGATIVE_SPACE -> layout.quietSide(crop, core, p.x < .5f)
            style == null && abs(p.x - .5f) >= .16f ->
                .55f * (layout.quietSide(crop, core, p.x < .5f) - .45f).coerceAtLeast(0f)
            else -> 0f
        }
        val excessBelow = (crop.bottom - core.bottom) / size - .16f
        val lowerDetail = if (subject.skyline && style != CompositionStyle.WIDE && excessBelow > 0f) {
            val strip = SubjectBox(crop.left, core.bottom, crop.right, crop.bottom)
            excessBelow * ((layout.subjectDetail(strip) - .015f) / .055f).coerceIn(0f, 1f)
        } else 0f
        return attention - .66f * competing - .63f * styleError + .46f * quiet +
            .60f * subject.strength - .7f * lowerDetail
    }

    private fun placements(style: CompositionStyle?, layout: SceneLayout, subject: SceneSubject): List<ScenePoint> = when (style) {
        CompositionStyle.THIRDS -> listOf(middle, ScenePoint(.37f, .36f), ScenePoint(.63f, .36f),
            ScenePoint(.37f, .56f), ScenePoint(.63f, .56f))
        CompositionStyle.CENTERED -> listOf(middle, ScenePoint(.5f, .52f))
        CompositionStyle.NEGATIVE_SPACE -> listOf(ScenePoint(.27f, .43f), ScenePoint(.73f, .43f))
        CompositionStyle.WIDE -> listOf(middle, ScenePoint(.50f, .56f))
        null -> {
            buildList {
                add(if (subject.kind == SceneKind.PERSON) ScenePoint(.5f, .38f) else middle)
                for ((x, rightSide) in listOf(.37f to true, .63f to false)) {
                    add(ScenePoint(x, .36f))
                    add(ScenePoint(x, .56f))
                    if (subject.skyline) continue
                    val quiet = layout.quietSide(full, subject.framingBounds, rightSide)
                    if (quiet > .62f && subject.kind != SceneKind.FOLIAGE) {
                        val shift = (quiet - .40f) * .15f
                        add(ScenePoint(if (rightSide) x - shift else x + shift, .44f))
                    }
                }
            }
        }
    }

    private fun boxAt(anchor: ScenePoint, position: ScenePoint, size: Float): SubjectBox {
        val left = (anchor.x - size * position.x).coerceIn(0f, 1f - size)
        val top = (anchor.y - size * position.y).coerceIn(0f, 1f - size)
        return SubjectBox(left, top, left + size, top + size)
    }

    private fun inContext(map: EnvironmentMap, view: Int, context: Int, box: SubjectBox): SubjectBox? {
        if (view == context) return box
        val points = map.projectPoints(view, context, ShotGeometry.corners(box)) ?: return null
        if (points.any { it.x !in 0f..1f || it.y !in 0f..1f } || !ShotGeometry.valid(points)) return null
        val bounds = SubjectBox(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        val height = bounds.bottom - bounds.top
        if (bounds.right - bounds.left !in (.83f * height)..(1.2f * height)) return null
        if (points.zip(ShotGeometry.corners(bounds)).any { (a, b) -> distance(a, b) > .12f * height }) return null
        return bounds
    }

    internal fun containsSubject(frame: SubjectBox, subject: SubjectBox): Boolean {
        val margin = width(frame) * .035f
        return subject.left >= frame.left + margin && subject.right <= frame.right - margin &&
            subject.top >= frame.top + margin && subject.bottom <= frame.bottom - margin
    }

    private fun distance(a: ScenePoint, b: ScenePoint) = hypot(a.x - b.x, a.y - b.y)
    private fun width(box: SubjectBox) = box.right - box.left
    private fun area(box: SubjectBox) = width(box) * (box.bottom - box.top)
    private fun intersection(a: SubjectBox, b: SubjectBox) =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
    private fun coverage(frame: SubjectBox, subject: SubjectBox) = intersection(frame, subject) / area(subject)
    internal fun overlap(a: SubjectBox, b: SubjectBox): Float {
        val shared = intersection(a, b)
        return shared / (area(a) + area(b) - shared)
    }
}
