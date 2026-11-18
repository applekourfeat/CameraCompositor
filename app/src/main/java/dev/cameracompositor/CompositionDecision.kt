package dev.cameracompositor

/** Source identities prevent a plan from silently becoming a crop of an unrelated view. */
data class ObservedView(val id: Long, val timeMs: Long, val sceneId: Long, val zoom: Float)
data class CompositionCandidate(
    val view: ObservedView,
    val crop: SubjectBox,
    val score: Float,
    val confidence: Float,
    val maxZoom: Float,
    val subjectId: Long? = null,
    val subjectCoverage: Float = 1f,
) {
    val zoom get() = view.zoom / (crop.right - crop.left)
}
sealed interface CompositionDecision {
    data class Keep(val viewId: Long) : CompositionDecision
    data class ObserveMore(val reason: String) : CompositionDecision
    data class Guide(val target: CompositionCandidate) : CompositionDecision
}

/** Experimental selection policy. It is intentionally absent from the public Find button
 * until an actual scorer passes the documented human and device acceptance gates. */
class ResearchCompositionPlanner(private val minGain: Float = .1f, private val minConfidence: Float = .7f) {
    fun decide(current: ObservedView, currentScore: Float, candidates: List<CompositionCandidate>,
               selectedSubjectId: Long? = null): CompositionDecision {
        if (!currentScore.isFinite() || currentScore !in 0f..1f) return CompositionDecision.ObserveMore("The current view has no calibrated score.")
        val eligible = candidates.filter { c ->
            val b = c.crop
            c.view.sceneId == current.sceneId && c.view.timeMs <= current.timeMs && current.timeMs - c.view.timeMs <= 8_000 &&
                c.view.zoom.isFinite() && c.view.zoom > 0 && c.score.isFinite() && c.score in 0f..1f &&
                c.confidence.isFinite() && c.confidence in minConfidence..1f &&
                listOf(b.left, b.top, b.right, b.bottom).all { it.isFinite() && it in 0f..1f } &&
                b.right > b.left && b.bottom > b.top && kotlin.math.abs((b.right - b.left) - (b.bottom - b.top)) < .002f &&
                c.maxZoom.isFinite() && c.zoom <= c.maxZoom && c.zoom <= c.view.zoom * 1.8f + .001f &&
                (selectedSubjectId == null || (c.subjectId == selectedSubjectId && c.subjectCoverage.isFinite() && c.subjectCoverage in .95f..1f))
        }
        val best = eligible.maxWithOrNull(compareBy<CompositionCandidate> { it.score }.thenBy { -it.zoom }.thenBy { it.view.id })
            ?: return CompositionDecision.ObserveMore("Observe another angle before choosing a frame.")
        return if (best.score - currentScore < minGain) CompositionDecision.Keep(current.id) else CompositionDecision.Guide(best)
    }
}

/** Tracking confidence is geometric evidence, not an aesthetic score. */
class GuideLifetime(private val expiryMs: Long = 3_000) {
    private var lastObserved: Long = 0
    fun start(now: Long) { lastObserved = now }
    fun observed(now: Long) { lastObserved = now }
    fun expired(now: Long): Boolean = now - lastObserved >= expiryMs
}
