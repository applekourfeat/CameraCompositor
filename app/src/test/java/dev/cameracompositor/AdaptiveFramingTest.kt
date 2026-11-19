package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test

class AdaptiveFramingTest {
    private val full = SubjectBox(0f, 0f, 1f, 1f)

    @Test fun `long uprights can guide a room without any recognized subject`() {
        val room = layout { x, y ->
            when {
                y in .10f.. .86f && x in .15f.. .53f -> 0xB4AA97
                y > .89f -> 0x35373D
                else -> 0x68605E
            }
        }
        val axis = room.uprightPair!!
        val choices = candidates(room)
        val shot = AdaptiveFraming.choose(choices, FloatArray(choices.size) { 4f })!!.shot
        assertEquals(LayoutCue.UPRIGHTS, shot.cue)
        assertTrue(shot.crop.left < axis.left && shot.crop.right > axis.right)
        assertTrue((shot.crop.left + shot.crop.right) * .5f < .47f)
    }

    @Test fun `empty and nearly black views offer no artificial frame`() {
        for (color in listOf(0x787878, 0x111111)) {
            val choices = candidates(layout { _, _ -> color })
            assertEquals(listOf(full), choices.map { it.crop })
            assertNull(AdaptiveFraming.choose(choices, FloatArray(choices.size) { 4f }))
        }
    }

    @Test fun `an explicit subject must remain visible even in a room-wide suggestion`() {
        val room = layout { x, y -> if (x < .58f && y > .16f && y < .88f) 0x9C9895 else 0x353535 }
        val selected = SceneSubject(SceneKind.OBJECTS, SubjectBox(.61f, .32f, .78f, .62f),
            ScenePoint(.695f, .47f), .29f)
        val distraction = SceneSubject(SceneKind.ARCHITECTURE, SubjectBox(.16f, .17f, .48f, .51f),
            ScenePoint(.33f, .33f), .36f)
        val choices = candidates(room, listOf(distraction, selected), required = selected)
        assertTrue(choices.size > 1)
        for (choice in choices.drop(1)) {
            assertTrue(ContextShots.containsSubject(choice.crop, selected.framingBounds))
            assertTrue(choice.subject == null || choice.subject == selected)
        }
    }

    @Test fun `an image-supported person stays whole in the wider shot`() {
        val room = layout { x, y -> if (x in .36f.. .88f && y in .17f.. .70f) 0xC5C1A8 else 0x74726E }
        val person = SceneSubject(SceneKind.PERSON, SubjectBox(.09f, .15f, .27f, .79f),
            ScenePoint(.18f, .35f), .35f, detected = true)
        val choices = candidates(room, listOf(person))
        assertTrue(choices.all { ContextShots.containsSubject(it.crop, person.framingBounds) })
    }

    @Test fun `a moderate reframe is available from a zoomed view`() {
        val room = layout { x, y -> if (x in .13f.. .48f && y in .07f.. .81f) 0xAAAFA7 else 0x383A3A }
        val choices = candidates(room, zoom = 2f)
        assertTrue(choices.size > 1)
        assertTrue(choices.all { it.crop.right - it.crop.left >= .90f })
    }

    @Test fun `the learned score can keep the original and geometry alone can guide without arming the shutter`() {
        val cleanEdges = ContextShot(0, SubjectBox(.04f, .05f, .88f, .89f), full, null, .24f, LayoutCue.EDGES)
        val choices = listOf(ContextShot(0, full, full, null, 0f), cleanEdges)
        assertNull(AdaptiveFraming.choose(choices, floatArrayOf(4.3f, 3.2f)))
        val outline = AdaptiveFraming.choose(choices, null)!!
        assertEquals(cleanEdges, outline.shot)
        assertFalse(outline.canAutoCapture)
    }

    @Test fun `a subtle off-center composition requires realignment before zoom`() {
        val crop = CropOption(-1, SubjectBox(.08f, .045f, .99f, .955f))
        val before = ShotGeometry.corners(crop.box)
        val tolerance = ShotGeometry.aimTolerance(crop, automatic = true)
        assertTrue(ShotGeometry.aimReady(before))
        assertFalse(ShotGeometry.aimReady(before, tolerance))
        assertTrue(ShotGeometry.aimReady(before.map { it.copy(x = it.x - .035f) }, tolerance))
    }

    private fun candidates(layout: SceneLayout, subjects: List<SceneSubject> = emptyList(),
                           required: SceneSubject? = null, zoom: Float = 1f): List<ContextShot> {
        val view = EnvironmentView(0, 1L, zoom, 3.2f, null)
        val map = EnvironmentMap(1L).apply { add(view) }
        return AdaptiveFraming.candidates(map, ShotEvidence(view, layout, subjects), 3.2f, required)
    }

    private fun layout(pixel: (Float, Float) -> Int): SceneLayout {
        val w = SceneLayout.WIDTH
        val h = SceneLayout.HEIGHT
        return SceneLayout(IntArray(w * h) { index -> pixel((index % w + .5f) / w, (index / w + .5f) / h) }, null)
    }
}
