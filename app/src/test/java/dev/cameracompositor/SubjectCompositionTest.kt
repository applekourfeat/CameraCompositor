package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test
import java.nio.FloatBuffer

class SubjectCompositionTest {
    @Test fun `tap picks that subject instead of an unrelated stronger detection`() {
        val objectAtTap = DetectedSubject(SubjectBox(.60f, .40f, .85f, .70f), 58, .61f)
        val person = DetectedSubject(SubjectBox(.20f, .18f, .49f, .87f), 0, .91f)
        val candidates = listOf(person, objectAtTap)
        assertEquals(objectAtTap, SubjectDetection.mainSubject(candidates, ScenePoint(.7f, .55f)))
        assertNull(SubjectDetection.mainSubject(candidates, ScenePoint(.04f, .04f)))
    }

    @Test fun `detector maps letterboxed output into the portrait viewfinder`() {
        val side = 416 / 32
        val index = 6 * side + 6
        val scores = FloatArray(side * side * 80).apply { this[index * 80 + 15] = .8f }
        val distances = FloatArray(side * side * 32) { -7f }
        for (edge in 0..3) distances[index * 32 + edge * 8 + 2] = 7f
        val subject = SubjectDetection.decode(FloatBuffer.wrap(scores), FloatBuffer.wrap(distances), 32, 312, 416).single()
        assertEquals(15, subject.category)
        assertEquals(.8f, subject.confidence, 0f)
        assertEquals((207.5f - 64 - 52) / 312, subject.box.left, .001f)
        assertEquals((207.5f + 64) / 416, subject.box.bottom, .001f)
    }

    @Test fun `nearby portrait subjects are kept together ahead of background furniture`() {
        val left = DetectedSubject(SubjectBox(.24f, .23f, .41f, .81f), 0, .63f)
        val right = DetectedSubject(SubjectBox(.48f, .24f, .66f, .80f), 0, .62f)
        val couch = DetectedSubject(SubjectBox(.06f, .52f, .97f, .95f), 57, .74f)
        val subject = SubjectDetection.mainSubject(listOf(couch, left, right))!!
        assertEquals(0, subject.category)
        assertEquals(SubjectBox(.24f, .23f, .66f, .81f), subject.box)
    }

    @Test fun `detected subject protects nearby context from a texture driven crop`() {
        val subject = DetectedSubject(SubjectBox(.65f, .62f, .88f, .86f), 15, .72f)
        val pixels = IntArray(SceneLayout.WIDTH * SceneLayout.HEIGHT) { i ->
            val x = i % SceneLayout.WIDTH
            val y = i / SceneLayout.WIDTH
            val detailArea = x in 33..78 && y in 41..98
            val gray = if (detailArea) (if ((x / 2 + y / 2) % 2 == 0) 29 else 203) else 121
            (gray shl 16) or (gray shl 8) or gray
        }
        val map = EnvironmentMap(1).apply { add(EnvironmentView(0, 1, 1f, 2.2f, null)) }
        val layouts = mapOf(0 to SceneLayout(pixels, null))
        val baseline = ObservedComposition.choose(map, layouts, 2.2f)
        assertTrue(baseline.toString(), baseline is EnvironmentDecision.Guide)
        val oldCrop = (baseline as EnvironmentDecision.Guide).crop
        assertTrue(oldCrop.right < subject.box.right || oldCrop.bottom < subject.box.bottom)
        val protected = ObservedComposition.choose(map, layouts, 2.2f, subject)
        if (protected is EnvironmentDecision.Guide) {
            val box = protected.crop
            assertTrue(box.left < subject.box.left && box.top < subject.box.top)
            assertTrue(box.right > subject.box.right && box.bottom > subject.box.bottom)
        } else assertTrue(protected is EnvironmentDecision.Keep)
    }
}
