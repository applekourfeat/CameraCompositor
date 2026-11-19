package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test

class SceneDiscoveryTest {
    private val map = EnvironmentMap(1).apply { add(EnvironmentView(0, 1, 1f, 2.2f, null, null, null)) }
    private val detail = SceneLayout(IntArray(SceneLayout.WIDTH * SceneLayout.HEIGHT) { i ->
        if ((i % SceneLayout.WIDTH / 2 + i / SceneLayout.WIDTH / 2) % 2 == 0) 0x848270 else 0x565044
    }, null)
    private val columns = SceneRegions.PROMPT_GROUP.size

    private fun scores(prompt: (row: Int, column: Int) -> Int) = FloatArray(12 * 12 * columns) { i ->
        if (i % columns == prompt(i / columns / 12, i / columns % 12)) .22f else .17f
    }

    private fun choose(scores: FloatArray, explore: Boolean = true, selected: SelectedSubject? = null,
                       expected: SubjectHint? = null) =
        SceneDiscovery.choose(CompositionStyle.THIRDS, map, mapOf(0 to detail), 2.2f, null, selected, scores, explore, expected)

    @Test fun `clipped foliage gives a new aim at the same zoom and cannot arm the shutter`() {
        val patches = scores { row, column -> if (row < 5 && column > 6) (row + column) % 3 else 12 }
        val result = choose(patches)
        assertEquals(SceneKind.FOLIAGE, result.region?.kind)
        val explore = result.plan.decision as EnvironmentDecision.Explore
        assertTrue(explore.aim.x > .5f && explore.aim.y < .5f)
        val requested = NextView.frame(explore.aim)
        assertEquals(1f, requested.right - requested.left, .0001f)
        assertTrue(requested.right > 1f && requested.top < 0f)
        assertFalse(result.plan.canAutoCapture)
        assertFalse(NextView.arrived(explore.aim))
        assertTrue(NextView.arrived(ScenePoint(.53f, .47f)))
    }

    @Test fun `the fresh observation and a tapped subject cannot trigger another unobserved turn`() {
        val patches = scores { row, column -> if (row < 5 && column > 6) 1 else 12 }
        assertFalse(choose(patches, explore = false).plan.decision is EnvironmentDecision.Explore)
        val picked = choose(patches, selected = SelectedSubject(ScenePoint(.32f, .33f), 0))
        assertNull(picked.region)
        assertFalse(picked.plan.decision is EnvironmentDecision.Explore)
    }

    @Test fun `sky against a tower distinguishes it from the lower architecture`() {
        val patches = scores { row, column ->
            if (row >= 5 || row in 1..4 && column in 2..5) 3 else 12
        }
        val subject = choose(patches).region!!
        assertEquals(SceneKind.ARCHITECTURE, subject.kind)
        assertTrue(subject.point.y < .37f)
        assertTrue(subject.bounds.bottom < .65f)
        assertTrue(subject.skyline)
    }

    @Test fun `a follow up keeps the tree subject and can leave the end of a branch out of frame`() {
        val expected = SubjectHint(SceneKind.FOLIAGE, ScenePoint(.67f, .36f))
        val patches = scores { row, column ->
            when {
                row in 2..5 && column in 6..11 -> 0
                row >= 7 -> 3
                else -> 12
            }
        }
        val result = choose(patches, explore = false, expected = expected)
        val crown = result.region!!
        assertEquals(SceneKind.FOLIAGE, crown.kind)
        assertEquals(1f, crown.bounds.right, .0001f)
        assertTrue(crown.framingBounds.right < 1f)
        assertTrue(result.plan.decision is EnvironmentDecision.Keep || result.plan.decision is EnvironmentDecision.Guide)
        assertTrue(choose(scores { _, _ -> 3 }, explore = false, expected = expected).plan.decision
            is EnvironmentDecision.ObserveMore)
    }

    @Test fun `background classifications leave the camera waiting`() {
        val result = choose(scores { _, _ -> 12 })
        assertNull(result.region)
        assertTrue(result.plan.decision is EnvironmentDecision.ObserveMore)
        assertFalse(result.plan.canAutoCapture)
    }
}
