package dev.cameracompositor

import kotlin.math.abs
import kotlin.math.ln

enum class LayoutCue(val title: String, val instruction: String) {
    UPRIGHTS("Structure", "Follow the outline to center the upright lines."),
    BALANCE("Balanced view", "Follow the outline to center the scene's detail."),
    EDGES("Cleaner edges", "Follow the outline to clear the edges."),
    SCENE("Whole scene", "Follow the outline to frame the scene.")
}

data class AdaptiveShot(val shot: ContextShot, val gain: Float, val canAutoCapture: Boolean)

/** Compare subject-focused and whole-scene framings against the actual opening view. */
object AdaptiveFraming {
    private val full = SubjectBox(0f, 0f, 1f, 1f)
    private val sceneSizes = floatArrayOf(.91f, .84f, .77f)
    private val stops = floatArrayOf(0f, .25f, .5f, .75f, 1f)

    @JvmOverloads
    fun candidates(map: EnvironmentMap, evidence: ShotEvidence, maxRelativeZoom: Float,
                   required: SceneSubject? = null, previousLandmark: SubjectBox? = null): List<ContextShot> {
        val (view, layout, subjects) = evidence
        val original = ContextShot(view.id, full, full, null, 0f)
        if (!layout.hasSceneDetail && required == null) {
            return listOf(original)
        }
        val protected = required ?: subjects.firstOrNull {
            it.detected && (it.kind == SceneKind.PERSON || it.kind == SceneKind.ANIMAL)
        }
        val strongSubject = subjects.any {
            it.skyline && it.strength >= .27f ||
                it.kind == SceneKind.FOLIAGE && it.framingBounds != it.bounds && it.strength >= .24f
        }
        val pair = if (required == null) subjectPair(evidence, protected, previousLandmark) else null
        val singles = ContextShots.candidates(map, listOf(evidence), null, maxRelativeZoom,
            requiredSubject = required).drop(1).filter { frame ->
            protected == null || ContextShots.containsSubject(frame.crop, protected.framingBounds)
        }
        // A partner can add context even when its detection is too weak to lead a shot.
        val paired = pair?.let {
            ContextShots.candidates(map, listOf(evidence.copy(subjects = listOf(it.subject))), null, maxRelativeZoom).drop(1)
        }.orEmpty()
        val anchored = (singles + paired).map { pair?.accountForPartner(it) ?: it }.sortedByDescending { it.framingGain }
        // Reserve a place for each observed subject before filling the remaining scoring slots.
        val diverse = (anchored.distinctBy { it.subject } + anchored).distinct().take(22)
        val context = sceneCandidates(evidence, maxRelativeZoom, protected, strongSubject)
        val result = mutableListOf(original)
        val adjustedContext = context.map { pair?.accountForPartner(it) ?: it }
        for (frame in (diverse + adjustedContext).sortedByDescending { it.framingGain }) {
            if (result.none { ContextShots.overlap(it.comparisonBox, frame.comparisonBox) > .93f }) {
                result.add(frame)
            }
        }
        return result
    }

    private data class PairFraming(val subject: SceneSubject, val first: SubjectBox, val second: SubjectBox,
                                   val weight: Float) {
        fun accountForPartner(frame: ContextShot): ContextShot {
            // Compare with the opening photo, where both subjects are fully visible.
            val retained = minOf(ContextShots.coverage(frame.crop, first), ContextShots.coverage(frame.crop, second))
            return frame.copy(framingGain = frame.framingGain - weight * (1f - retained.coerceIn(0f, 1f)))
        }
    }

    private fun subjectPair(evidence: ShotEvidence, protected: SceneSubject?, previousLandmark: SubjectBox?): PairFraming? {
        val primary = protected ?: evidence.subjects.firstOrNull { it.skyline } ?: return null
        val first = primary.framingBounds
        val partners = mutableListOf<Pair<SceneSubject, Float>>()
        if (primary.detected) {
            val detections = SubjectDetection.distinctSubjects(evidence.detections)
            val anchor = detections.maxByOrNull { ContextShots.overlap(it.box, first) }
            if (anchor != null && ContextShots.overlap(anchor.box, first) > .75f) {
                for (other in detections) {
                    if (other.category == anchor.category && other.confidence >= .45f && other !== anchor) {
                        partners.add(ContextShots.detectedSubject(other) to 1.25f)
                    }
                }
            }
        }
        for (other in evidence.subjects) {
            if (primary.skyline && other.skyline || primary.kind == SceneKind.PERSON &&
                (other.outlinedDetail || other.skyline || other.kind == SceneKind.FOLIAGE)) {
                partners.add(other to .85f)
            }
        }
        if (primary.kind == SceneKind.PERSON && previousLandmark != null) {
            val b = previousLandmark
            val center = ScenePoint((b.left + b.right) * .5f, (b.top + b.bottom) * .5f)
            partners.add(SceneSubject(SceneKind.OBJECTS, b, center, .25f, outlinedDetail = true) to .85f)
        }
        val firstSize = maxOf(first.right - first.left, first.bottom - first.top)
        val partner = partners.filter { (other, _) ->
            val b = other.framingBounds
            val size = maxOf(b.right - b.left, b.bottom - b.top)
            val separation = kotlin.math.hypot(other.point.x - primary.point.x, other.point.y - primary.point.y)
            size / firstSize in .4f..2.5f && separation in .14f.. .60f &&
                ContextShots.overlap(first, b) < .15f && ContextShots.containsSubject(full, b)
        }.maxByOrNull { (other, weight) -> weight * other.strength } ?: return null
        val second = partner.first.framingBounds
        val box = SubjectBox(minOf(first.left, second.left), minOf(first.top, second.top),
            maxOf(first.right, second.right), maxOf(first.bottom, second.bottom))
        val center = ScenePoint((box.left + box.right) / 2, (box.top + box.bottom) / 2)
        return PairFraming(primary.copy(bounds = box, framingBounds = box, point = center, paired = true),
            first, second, partner.second)
    }

    fun choose(frames: List<ContextShot>, scores: FloatArray?): AdaptiveShot? {
        require(frames.isNotEmpty() && (scores == null || scores.size == frames.size && scores.all(Float::isFinite)))
        val wide = scores?.first()
        return frames.drop(1).mapIndexedNotNull { index, frame ->
            val photoScore = scores?.get(index + 1)
            val photoGain = if (wide != null && photoScore != null) photoScore - wide else 0f
            val isScene = frame.cue != null
            val gain = frame.framingGain + (if (isScene) .45f else .40f) * photoGain
            if (isScene && wide != null && wide >= 4.30f && frame.framingGain < .015f) return@mapIndexedNotNull null
            val requiredGain = when {
                isScene && scores == null -> .10f
                isScene -> .068f
                scores == null -> .205f
                else -> .155f
            }
            val minimumPhotoGain = when {
                frame.subject?.outlinedDetail == true -> -.32f
                isScene -> -.58f
                else -> -1.26f
            }
            val acceptablePhoto = photoScore == null || (photoScore >= 2.5f && photoGain >= minimumPhotoGain)
            if (gain < requiredGain || !acceptablePhoto) return@mapIndexedNotNull null
            val trustedSubject = frame.subject?.let {
                if (it.detected) it.strength >= .30f else photoScore != null && (it.skyline || it.strength >= .28f)
            } == true
            val trustedScene = isScene && photoScore != null && photoGain >= -.03f && frame.framingGain >= .17f
            val autoCapture = gain >= .23f && (trustedSubject || trustedScene)
            AdaptiveShot(frame, gain, autoCapture)
        }.maxByOrNull { it.gain }
    }

    private fun sceneCandidates(evidence: ShotEvidence, maxRelativeZoom: Float, protected: SceneSubject?,
                                strongSubject: Boolean): List<ContextShot> {
        val (view, layout) = evidence
        if (!layout.hasSceneDetail) return emptyList()
        val original = layout.rate(full, useDepth = false)
        val balanceCenter = layout.focus?.let { (it.left + it.right) * .5f } ?: .5f
        val uprights = layout.uprightPair
        val zoomLimit = minOf(maxRelativeZoom, view.maxRelativeZoom, maxOf(1.14f, 1.85f / view.zoom))
        val shortlist = mutableListOf<ContextShot>()
        for (size in sceneSizes) {
            if (1 / size > zoomLimit + .001f) continue
            val options = mutableListOf<ContextShot>()
            val span = 1f - size
            val lefts = (stops.map { it * span } + (balanceCenter - size * .5f).coerceIn(0f, span) +
                listOfNotNull(uprights?.let { (it.center - size * .5f).coerceIn(0f, span) })).distinct()
            for (left in lefts) for (height in stops) {
                val top = height * span
                val box = SubjectBox(left, top, left + size, top + size)
                if (protected != null && !ContextShots.containsSubject(box, protected.framingBounds)) continue
                val frame = layout.rate(box, useDepth = false)
                if (frame.retained < maxOf(.65f, size * size - .075f) || frame.clipped > original.clipped + .075f) continue
                val balance = original.balance - frame.balance
                val clearEdges = original.boundary - frame.boundary
                val uprightGain = uprights?.takeIf { it.left > box.left + .035f && it.right < box.right - .035f }
                    ?.let { (abs(it.center - .5f) - abs((it.center - left) / size - .5f)) * it.support } ?: 0f
                val usefulArea = frame.retained - size * size
                val zoomCost = (.13f + 3.8f * ln(view.zoom.coerceAtLeast(1f))) * ln(1f / size)
                val gain = frame.quality - original.quality + .68f * clearEdges + .48f * balance +
                    .85f * uprightGain + .72f * usefulArea - .31f * (1f - frame.retained) - zoomCost
                // A small geometry penalty can be outweighed by a better learned composition score.
                if (gain < -.12f) continue
                val cue = when {
                    uprightGain >= .035f && .85f * uprightGain > .55f * balance -> LayoutCue.UPRIGHTS
                    clearEdges >= .030f || usefulArea >= .14f -> LayoutCue.EDGES
                    balance >= .018f -> LayoutCue.BALANCE
                    else -> LayoutCue.SCENE
                }
                options.add(ContextShot(view.id, box, box, null, gain - if (strongSubject) .20f else 0f, cue))
            }
            takeDistinct(options, shortlist)
        }
        return shortlist
    }

    private fun takeDistinct(options: List<ContextShot>, shortlist: MutableList<ContextShot>) {
        val limit = shortlist.size + 5
        for (option in options.sortedByDescending { it.framingGain }) {
            if (shortlist.none { ContextShots.overlap(it.crop, option.crop) > .94f }) shortlist.add(option)
            if (shortlist.size >= limit) return
        }
    }
}
