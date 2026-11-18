package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test

class CompositionDecisionTest {
    private val current = ObservedView(12, 10_000, 1, 1f)
    private val candidate = CompositionCandidate(ObservedView(3, 8_000, 1, 1f), SubjectBox(.1f, .1f, .9f, .9f), .9f, .85f, 2f)
    private val planner = ResearchCompositionPlanner()

    @Test fun `guide refers to the actual observed frame rather than current frame`() {
        val decision = planner.decide(current, .5f, listOf(candidate)) as CompositionDecision.Guide
        assertEquals(3, decision.target.view.id); assertEquals(1.25f, decision.target.zoom, .001f)
    }
    @Test fun `already good view remains unchanged`() {
        assertEquals(CompositionDecision.Keep(current.id), planner.decide(current, .88f, listOf(candidate)))
    }
    @Test fun `low confidence and absent assessments request more observations`() {
        assertTrue(planner.decide(current, .5f, emptyList()) is CompositionDecision.ObserveMore)
        assertTrue(planner.decide(current, .5f, listOf(candidate.copy(confidence = .2f))) is CompositionDecision.ObserveMore)
    }
    @Test fun `stale views other scenes and impossible zoom are rejected`() {
        val invalid = listOf(candidate.copy(view = candidate.view.copy(sceneId = 2)),
            candidate.copy(view = candidate.view.copy(timeMs = 1)), candidate.copy(maxZoom = 1.1f),
            candidate.copy(score = Float.NaN), candidate.copy(crop = SubjectBox(-.1f, 0f, .5f, .6f)))
        for (c in invalid) assertTrue(planner.decide(current, .5f, listOf(c)) is CompositionDecision.ObserveMore)
    }
    @Test fun `explicit subject must be identified and preserved in candidate view`() {
        assertTrue(planner.decide(current, .5f, listOf(candidate), 7) is CompositionDecision.ObserveMore)
        assertTrue(planner.decide(current, .5f, listOf(candidate.copy(subjectId = 7, subjectCoverage = .5f)), 7) is CompositionDecision.ObserveMore)
        assertTrue(planner.decide(current, .5f, listOf(candidate.copy(subjectId = 7)), 7) is CompositionDecision.Guide)
    }
    @Test fun `guide expires after lost tracking and observations extend it`() {
        val lifetime = GuideLifetime()
        lifetime.start(1_000); assertFalse(lifetime.expired(3_999)); assertTrue(lifetime.expired(4_000))
        lifetime.observed(3_500); assertFalse(lifetime.expired(4_000)); assertTrue(lifetime.expired(6_500))
    }
}
