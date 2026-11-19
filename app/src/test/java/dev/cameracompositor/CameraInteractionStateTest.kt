package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test

class CameraInteractionStateTest {
    @Test fun `full view result restores buttons without a separate refresh call`() {
        var rendered: CameraControls? = null
        val state = CameraInteractionState { rendered = it }
        state.ready = true; state.scanning = true
        assertFalse(FramingFeedback.hasSuggestion(ModelChoice(0, "Full view ranked highest")))
        state.scanning = false
        assertEquals(CameraControls(true, true, false), rendered)
    }
    @Test fun `declining a proposed crop leaves camera controls ready`() {
        var rendered: CameraControls? = null
        val state = CameraInteractionState { rendered = it }
        state.ready = true; state.scanning = true
        assertTrue(FramingFeedback.hasSuggestion(ModelChoice(3, "A crop")))
        state.scanning = false
        // Review does not start a guide. Dismissal needs no extra control mutation.
        assertEquals(CameraControls(true, true, false), rendered)
    }
    @Test fun `a pending online scan leaves the shutter available`() {
        val state = CameraInteractionState {}
        state.ready = true; state.scanning = true
        assertTrue(state.controls.captureEnabled)
        assertFalse(state.controls.flipEnabled)
        assertTrue(state.controls.cancelVisible)
        state.scanning = false; state.saving = true
        assertEquals(CameraControls(false, false, false), state.controls)
        state.saving = false
        assertEquals(CameraControls(true, true, false), state.controls)
    }
    @Test fun `timeout and cancel restore controls without creating a guide`() {
        val history = mutableListOf<CameraControls>()
        val state = CameraInteractionState { history.add(it) }
        state.ready = true
        repeat(2) {
            state.scanning = true; state.scanning = false
            assertEquals(CameraControls(true, true, false), history.last())
        }
    }
    @Test fun `only an accepted guide keeps its cancel action visible`() {
        val state = CameraInteractionState {}
        state.ready = true; state.guiding = true
        assertTrue(state.controls.cancelVisible)
        state.guiding = false
        assertFalse(state.controls.cancelVisible)
        state.ready = false
        assertEquals(CameraControls(false, false, false), state.controls)
    }
    @Test fun `online full frame result is also abstention`() {
        assertFalse(FramingFeedback.hasSuggestion(ModelChoice(-1, "Wide view", SubjectBox(0f, 0f, 1f, 1f))))
        assertTrue(FramingFeedback.hasSuggestion(ModelChoice(-1, "Crop", SubjectBox(.1f, .1f, .9f, .9f))))
        assertThrows(IllegalArgumentException::class.java) { FramingFeedback.hasSuggestion(ModelChoice(90, "Invalid")) }
    }
}
