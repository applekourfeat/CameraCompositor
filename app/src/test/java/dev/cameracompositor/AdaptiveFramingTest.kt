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

    @Test fun distinctObjectsCompeteEvenWhenAFurnitureDetectionDominates() {
        val room = layout { x, y -> when {
            x < .26f -> 0x353535
            ((x * 45).toInt() + (y * 51).toInt()) % 2 == 0 -> 0xC1AE97
            else -> 0x514941
        } }
        val chair = DetectedSubject(SubjectBox(.05f, .08f, .91f, .88f), 56, .82f)
        val smaller = DetectedSubject(SubjectBox(.56f, .31f, .74f, .53f), 75, .58f)
        val subjects = ContextShots.observedSubjects(room, null, listOf(chair, smaller, smaller))
        assertEquals(1, subjects.count { it.bounds == smaller.box })
        val options = candidates(room, subjects)
        val close = options.filter { it.subject?.bounds == smaller.box && it.crop.right - it.crop.left < .5f }
        assertTrue(close.isNotEmpty())
        assertTrue(close.all { ContextShots.containsSubject(it.crop, smaller.box) })
        val ranked = AdaptiveFraming.choose(options, FloatArray(options.size) { 4f })
        assertEquals(smaller.box, ranked?.shot?.subject?.bounds)
    }

    @Test fun higherZoomNeedsLightAndNeverExceedsTheCameraLimit() {
        val room = layout { x, y -> when {
            x < .3f -> 0x030303
            ((x * 45).toInt() + (y * 51).toInt()) % 2 == 0 -> 0x404040
            else -> 0x090909
        } }
        val subject = SceneSubject(SceneKind.OBJECTS, SubjectBox(.45f, .32f, .62f, .54f),
            ScenePoint(.53f, .43f), .34f, detected = true)
        assertTrue(candidates(room, listOf(subject)).all { 1 / (it.crop.right - it.crop.left) <= 1.851f })
        val view = EnvironmentView(0, 1, 1f, 1.35f, null)
        val map = EnvironmentMap(1).apply { add(view) }
        val options = AdaptiveFraming.candidates(map, ShotEvidence(view, room, listOf(subject)), 3.2f)
        assertTrue(options.all { 1 / (it.crop.right - it.crop.left) <= 1.351f })
    }

    @Test fun framedDetailNeedsAnEnclosedSubject() {
        val framed = layout { x, y -> if (x in .26f.. .57f && y in .32f.. .59f) 0x373737 else 0xBABABA }
        val stripes = layout { x, _ -> if (x in .26f.. .57f) 0x373737 else 0xBABABA }
        val box = framed.framedDetail!!
        assertTrue(box.left in .20f.. .28f && box.right in .55f.. .62f)
        assertTrue(box.top in .28f.. .33f && box.bottom in .58f.. .65f)
        assertNull(stripes.framedDetail)
        assertTrue(ContextShots.observedSubjects(framed, null, emptyList()).single().outlinedDetail)
    }

    @Test fun weakClippedAnimalDetectionCannotVetoTheEntireScene() {
        val room = layout { x, y -> if (x in .26f.. .57f && y in .32f.. .59f) 0x373737 else 0xBABABA }
        val uncertain = DetectedSubject(SubjectBox(.34f, 0f, 1f, .65f), 15, .475f)
        val subjects = ContextShots.observedSubjects(room, null, listOf(uncertain))
        assertTrue(subjects.none { it.detected })
        assertTrue(candidates(room, subjects).size > 1)
    }

    @Test fun secondPersonCanSupportAFrameWithoutQualifyingAsASoloSubject() {
        val room = texturedScene()
        val lead = DetectedSubject(SubjectBox(.20f, .48f, .32f, .73f), 0, .79f)
        val other = DetectedSubject(SubjectBox(.55f, .47f, .65f, .70f), 0, .52f)
        val subject = ContextShots.detectedSubject(lead)
        val view = EnvironmentView(0, 1L, 1f, 3.2f, null)
        val map = EnvironmentMap(1L).apply { add(view) }
        val evidence = ShotEvidence(view, room, listOf(subject), listOf(lead, other))
        val choices = AdaptiveFraming.candidates(map, evidence, 3.2f)
        assertTrue(choices.any { it.subject?.paired == true })
        val winner = AdaptiveFraming.choose(choices, FloatArray(choices.size) { 4f })!!
        assertTrue(ContextShots.containsSubject(winner.shot.crop, lead.box))
        assertTrue(ContextShots.containsSubject(winner.shot.crop, other.box))
        val selected = AdaptiveFraming.candidates(map, evidence, 3.2f, subject)
        assertTrue(selected.none { it.subject?.paired == true })
        assertTrue(selected.any { !ContextShots.containsSubject(it.crop, other.box) })
    }

    @Test fun oneBirdAndDuplicateBoxesKeepTheirSoloFraming() {
        val room = texturedScene()
        val bird = DetectedSubject(SubjectBox(.4f, .35f, .59f, .60f), 14, .80f)
        val view = EnvironmentView(0, 1L, 1f, 3.2f, null)
        val map = EnvironmentMap(1L).apply { add(view) }
        val evidence = ShotEvidence(view, room, listOf(ContextShots.detectedSubject(bird)))
        val solo = AdaptiveFraming.candidates(map, evidence, 3.2f)
        assertTrue(solo.any { it.subject != null })
        assertEquals(solo, AdaptiveFraming.candidates(map, evidence.copy(detections = listOf(bird, bird)), 3.2f))
    }

    private fun texturedScene() = layout { x, y -> when {
        y < .24f -> 0x555555
        ((x * 45).toInt() + (y * 51).toInt()) % 2 == 0 -> 0xC7C7A9
        else -> 0x484858
    } }

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
