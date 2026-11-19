package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test

class ContextShotsTest {
    private val full = SubjectBox(0f, 0f, 1f, 1f)
    private val layout = SceneLayout(IntArray(SceneLayout.WIDTH * SceneLayout.HEIGHT) { 0x7C8491 }, null)

    @Test fun `a useful subject shot keeps the core and competes with the opening view`() {
        val view = EnvironmentView(1, 1L, .65f, 3.2f, null)
        val map = EnvironmentMap(1L).apply { add(view) }
        val core = SubjectBox(.46f, .12f, .74f, .39f)
        val branch = SubjectBox(.41f, 0f, 1f, .60f)
        val tree = SceneSubject(SceneKind.FOLIAGE, branch, ScenePoint(.61f, .26f), .29f, framingBounds = core)
        val building = SceneSubject(SceneKind.ARCHITECTURE, SubjectBox(.44f, .73f, .77f, 1f),
            ScenePoint(.60f, .87f), .24f)
        val shots = ContextShots.candidates(map, listOf(ShotEvidence(view, layout, listOf(tree, building))),
            CompositionStyle.THIRDS, 3.2f)
        assertEquals(full, shots.first().crop)
        val winner = ContextShots.choose(shots, FloatArray(shots.size) { if (it == 0) 4.2f else 3.8f })!!.shot
        assertEquals(SceneKind.FOLIAGE, winner.subject?.kind)
        assertTrue(winner.crop.left < core.left && winner.crop.right > core.right &&
            winner.crop.top < core.top && winner.crop.bottom > core.bottom)
        assertTrue(winner.crop.bottom < building.point.y)
    }

    @Test fun `an already prominent centered subject and a badly scored alternative can stay wide`() {
        val view = EnvironmentView(0, 1L, 1f, 2f, null)
        val map = EnvironmentMap(1L).apply { add(view) }
        val house = SceneSubject(SceneKind.ARCHITECTURE, SubjectBox(.21f, .17f, .79f, .72f),
            ScenePoint(.50f, .44f), .29f)
        val shots = ContextShots.candidates(map, listOf(ShotEvidence(view, layout, listOf(house))),
            CompositionStyle.CENTERED, 1.85f)
        assertNull(ContextShots.choose(shots, FloatArray(shots.size) { 4.0f }))
        val rejected = ContextShot(0, SubjectBox(.2f, .2f, .8f, .8f), SubjectBox(.2f, .2f, .8f, .8f),
            house, 2f)
        assertNull(ContextShots.choose(listOf(shots.first(), rejected), floatArrayOf(4.4f, 2.9f)))
    }

    @Test fun `an earlier real frame can supply a target while the new view supplies the score context`() {
        fun shift(n: Float) = ImageProjection(listOf(1f, 0f, n, 0f, 1f, 0f, 0f, 0f, 1f))
        val previous = EnvironmentView(0, 1L, .6f, 3.2f, null)
        val current = EnvironmentView(1, 2L, .6f, 3.2f,
            ViewRegistration(60, 72, .14f, .6f, .45f, shift(.14f), shift(-.14f)), 0)
        val map = EnvironmentMap(1L).apply { add(previous); add(current) }
        val tree = SceneSubject(SceneKind.FOLIAGE, SubjectBox(.28f, .20f, .52f, .43f), ScenePoint(.39f, .31f), .30f)
        val present = SceneSubject(SceneKind.FOLIAGE, SubjectBox(.39f, .16f, .69f, .46f), ScenePoint(.55f, .30f), .24f)
        val shots = ContextShots.candidates(map,
            listOf(ShotEvidence(current, layout, listOf(present)), ShotEvidence(previous, layout, listOf(tree))),
            CompositionStyle.THIRDS, 3.2f)
        val earlier = shots.first { it.viewId == 0 }
        assertEquals(earlier.crop.left + .14f, earlier.comparisonBox.left, .0001f)
        assertTrue(shots.all { it.comparisonBox.left >= 0f && it.comparisonBox.right <= 1f &&
            it.comparisonBox.top >= 0f && it.comparisonBox.bottom <= 1f })
    }
}
