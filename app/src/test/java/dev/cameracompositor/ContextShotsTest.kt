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

    @Test fun `connected facade evidence against the sky anchors a shot when patches disagree`() {
        val grid = SceneRegions.GRID
        val pixels = IntArray(SceneLayout.WIDTH * SceneLayout.HEIGHT) { i ->
            val x = i % SceneLayout.WIDTH
            val y = i / SceneLayout.WIDTH
            val tower = x in 36 until 72 && y >= 32
            if (tower || y >= 80) {
                if ((x + y) % 4 < 2) 0x777777 else 0xA8A8A8
            } else 0x7FA8CD
        }
        val city = SceneLayout(pixels, null)
        val prompts = SceneRegions.PROMPT_GROUP.size
        val scores = FloatArray(grid * grid * prompts) { .04f }
        for (y in 0 until grid) for (x in 0 until grid) {
            val cell = (y * grid + x) * prompts
            when {
                y in 2..3 && x in 3..5 -> {
                    scores[cell + 3] = .144f
                    scores[cell + 7] = .161f
                }
                y < 4 -> scores[cell + 12] = .25f
                else -> scores[cell + 11] = .21f
            }
        }
        val building = ContextShots.subjects(city, scores, null).first { it.kind == SceneKind.ARCHITECTURE }
        assertTrue(building.framingBounds.top < .17f)
        assertTrue(building.framingBounds.left < .25f && building.framingBounds.right > .50f)
        val view = EnvironmentView(0, 1L, 1f, 1.85f, null)
        val map = EnvironmentMap(1L).apply { add(view) }
        val shots = ContextShots.candidates(map, listOf(ShotEvidence(view, city, listOf(building))),
            CompositionStyle.THIRDS, 1.85f)
        val chosen = ContextShots.choose(shots, FloatArray(shots.size) { 4f })!!.shot
        assertTrue(chosen.crop.bottom >= building.framingBounds.bottom && chosen.crop.bottom < .89f)
    }

    @Test fun `a framing is compared against its own subject when nearby regions share a category`() {
        val view = EnvironmentView(0, 1L, 1f, 2f, null)
        val map = EnvironmentMap(1L).apply { add(view) }
        val house = SceneSubject(SceneKind.ARCHITECTURE, SubjectBox(.21f, .21f, .57f, .64f),
            ScenePoint(.39f, .42f), .31f)
        val tower = SceneSubject(SceneKind.ARCHITECTURE, SubjectBox(.47f, .18f, .61f, .51f),
            ScenePoint(.54f, .35f), .22f)
        fun bestTowerGain(subjects: List<SceneSubject>) =
            ContextShots.candidates(map, listOf(ShotEvidence(view, layout, subjects)), CompositionStyle.THIRDS, 2f)
                .first { it.subject == tower }.framingGain
        assertEquals(bestTowerGain(listOf(house, tower)), bestTowerGain(listOf(tower, house)), .0001f)
    }
}
