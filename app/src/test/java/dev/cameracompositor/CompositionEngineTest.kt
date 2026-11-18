package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test

class CompositionEngineTest {
    private fun box(x: Float, y: Float) = SubjectBox(x - .1f, y - .1f, x + .1f, y + .1f)
    @Test fun `panning advice moves subject toward target`() {
        assertEquals(Cue.RIGHT, CompositionEngine.evaluate(box(.75f, .5f), .5f))
        assertEquals(Cue.LEFT, CompositionEngine.evaluate(box(.25f, .5f), .5f))
        assertEquals(Cue.DOWN, CompositionEngine.evaluate(box(.5f, .75f), .5f))
        assertEquals(Cue.UP, CompositionEngine.evaluate(box(.5f, .25f), .5f))
    }
    @Test fun `each target can align`() {
        listOf(1f / 3f, .5f, 2f / 3f).forEach {
            assertEquals(Cue.ALIGNED, CompositionEngine.evaluate(box(it, .5f), it))
        }
    }
    @Test fun `clipping wins over alignment`() {
        assertEquals(Cue.SPACE, CompositionEngine.evaluate(SubjectBox(.1f, -.1f, .9f, .9f), .5f))
    }
    @Test fun `missing and invalid subjects cannot align`() {
        assertEquals(Cue.SEARCHING, CompositionEngine.evaluate(null, .5f))
        assertEquals(Cue.SEARCHING, CompositionEngine.evaluate(SubjectBox(1f, 1f, 0f, 0f), .5f))
        assertEquals(Cue.SEARCHING, CompositionEngine.evaluate(box(Float.NaN, .5f), .5f))
    }
    @Test fun `success requires stable alignment and resets after movement`() {
        val hold = AlignmentHold()
        assertFalse(hold.update(Cue.ALIGNED, 1000))
        assertFalse(hold.update(Cue.ALIGNED, 1600))
        assertTrue(hold.update(Cue.ALIGNED, 1700))
        assertFalse(hold.update(Cue.LEFT, 1800))
        assertFalse(hold.update(Cue.ALIGNED, 1900))
    }
}
