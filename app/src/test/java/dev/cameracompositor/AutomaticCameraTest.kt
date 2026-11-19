package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test

class AutomaticCameraTest {
    private val corners = ShotGeometry.corners(SubjectBox(0f, 0f, 1f, 1f))

    @Test fun `three second countdown fires once after steady framing`() {
        val shutter = AutomaticShutter()
        fun at(time: Long) = shutter.update(time, time + 15, corners, true)
        for (time in 0L..400L step 100) assertEquals(ShutterUpdate.Waiting, at(time))
        assertEquals(ShutterUpdate.Counting(3), at(500))
        for (time in 600L..1400L step 100) assertEquals(ShutterUpdate.Counting(3), at(time))
        for (time in 1500L..2400L step 100) assertEquals(ShutterUpdate.Counting(2), at(time))
        for (time in 2500L..3400L step 100) assertEquals(ShutterUpdate.Counting(1), at(time))
        assertEquals(ShutterUpdate.Capture, at(3500))
        assertEquals(ShutterUpdate.Waiting, at(3600))
    }

    @Test fun `motion and dropped observations reset the countdown`() {
        val shutter = AutomaticShutter()
        for (time in 0L..700L step 100) shutter.update(time, time, corners, true)
        val moved = corners.map { ScenePoint(it.x + .027f, it.y) }
        assertEquals(ShutterUpdate.Waiting, shutter.update(800, 800, moved, true))
        assertEquals(ShutterUpdate.Waiting, shutter.update(900, 1300, moved, true))
        assertEquals(ShutterUpdate.Waiting, shutter.update(3600, 3600, moved, true))
        assertEquals(ShutterUpdate.Waiting, shutter.update(3700, 3700, moved, false))
        assertEquals(ShutterUpdate.Waiting, shutter.update(3800, 3800, corners, true))
    }

    @Test fun `orientation alone can never sustain the shutter countdown`() {
        val shutter = AutomaticShutter()
        for (time in 0L..700L step 100) shutter.update(time, time, corners, true)
        for (time in 800L..4900L step 100) {
            assertEquals(ShutterUpdate.Waiting, shutter.update(time, time, corners, ready = true, verified = false))
        }
        assertEquals(ShutterUpdate.Waiting, shutter.update(5000, 5000, corners, ready = true))
    }

    @Test fun `lost guidance can retry once in place but a captured scene cannot`() {
        val still = IntArray(96) { it * 2 }
        val gate = AutomaticScanGate()
        assertTrue(gate.retryOnce(100))
        assertFalse(gate.ready(LiveScene(699, still), 699))
        assertTrue(gate.ready(LiveScene(701, still), 701))
        assertFalse(gate.retryOnce(701))
        gate.waitForChange(900, true, LiveScene(899, still))
        assertFalse(gate.retryOnce(1000))
        assertFalse(gate.ready(LiveScene(1700, still), 1700))
    }

    @Test fun `same scene and a quick blur cannot rearm scan`() {
        val used = IntArray(96) { if (it % 12 < 6) 50 else 155 }
        val other = IntArray(96) { if (it / 12 < 4) 180 else 25 }
        val gate = AutomaticScanGate()
        gate.waitForChange(100, true, LiveScene(100, used))
        assertFalse(gate.ready(LiveScene(500, other), 500))
        assertFalse(gate.ready(LiveScene(800, IntArray(96) { used[it] + 22 }), 800))
        assertFalse(gate.ready(LiveScene(900, other), 900))
        assertFalse(gate.ready(LiveScene(1100, used), 1100))
        assertFalse(gate.ready(LiveScene(1250, other), 1250))
        assertFalse(gate.ready(LiveScene(1400, other), 1400))
        assertTrue(gate.ready(LiveScene(1800, other), 1800))
    }

    @Test fun `zoom ramps quickly with bounded jumps after a camera stall`() {
        val ramp = ZoomRamp(1f, 2.2f)
        var last = 1f
        for (ms in 32L..1000L step 32) {
            val next = ramp.next(last, ms)
            assertTrue(next >= last - .0001f)
            assertTrue(next <= 2.2f + .0001f && next / last < 1.069f)
            last = next
        }
        assertEquals(2.2f, last, .005f)
        assertTrue(ramp.next(1.05f, 1600) < 1.125f)
        val out = ZoomRamp(2.2f, 1f)
        assertTrue(out.next(2.2f, 300) < 2.2f)
        assertTrue(out.next(2.2f, 900) > 2.05f)
    }

    @Test fun `adaptive photo uses ordinary JPEG for uncertain or midtone lighting`() {
        assertEquals(PhotoLighting.ORDINARY, classifyLighting(null))
        assertEquals(PhotoLighting.ORDINARY, classifyLighting(IntArray(96) { -1 }))
        assertEquals(PhotoLighting.ORDINARY, classifyLighting(IntArray(96) { if (it < 12) 28 else 128 }))
        assertEquals(PhotoLighting.ORDINARY, classifyLighting(IntArray(96) { if (it < 26) 215 else 130 }))
    }

    @Test fun `adaptive photo distinguishes a backlit scene from pervasive low light`() {
        assertEquals(PhotoLighting.DIM, classifyLighting(IntArray(96) { if (it < 74) 44 else 86 }))
        assertEquals(PhotoLighting.BACKLIT, classifyLighting(IntArray(96) { if (it < 23) 35 else 215 }))
    }
}
