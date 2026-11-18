package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test

class ShotGeometryTest {
    @Test fun `all candidate crops stay inside the source and preserve aspect ratio`() {
        assertEquals(9, ShotGeometry.options.size)
        for (crop in ShotGeometry.options) {
            val b = crop.box
            assertTrue(b.left >= 0 && b.top >= 0 && b.right <= 1 && b.bottom <= 1)
            assertEquals(b.right - b.left, b.bottom - b.top, .0001f)
            assertTrue(crop.zoom in 1f..1.81f)
            assertTrue(ShotGeometry.valid(ShotGeometry.corners(b)))
        }
    }
    @Test fun `full frame is ready and requests no zoom`() {
        val points = ShotGeometry.corners(ShotGeometry.options[0].box)
        assertTrue(ShotGeometry.framingReady(points))
        assertEquals(1f, ShotGeometry.desiredZoom(1f, points, 1f, 8f)!!, .001f)
    }
    @Test fun `zoom waits until camera aim matches the planned center`() {
        val points = ShotGeometry.corners(ShotGeometry.options[2].box)
        assertFalse(ShotGeometry.aimReady(points))
        assertNull(ShotGeometry.desiredZoom(1f, points, 1f, 8f))
    }
    @Test fun `centered crop determines zoom from its visible size`() {
        val points = ShotGeometry.corners(ShotGeometry.options[6].box)
        assertEquals(1.8f, ShotGeometry.desiredZoom(1f, points, 1f, 8f)!!, .001f)
        assertFalse(ShotGeometry.framingReady(points))
    }
    @Test fun `zoom respects hardware limit and preserves baseline`() {
        val points = ShotGeometry.corners(ShotGeometry.options[6].box)
        assertEquals(1.4f, ShotGeometry.desiredZoom(1f, points, 1f, 1.4f)!!, .001f)
        val wide = listOf(ScenePoint(-.2f, -.2f), ScenePoint(1.2f, -.2f), ScenePoint(1.2f, 1.2f), ScenePoint(-.2f, 1.2f))
        assertEquals(1f, ShotGeometry.desiredZoom(1f, wide, 1f, 8f)!!, .001f)
    }
    @Test fun `invalid and folded polygons cannot trigger alignment or zoom`() {
        val valid = ShotGeometry.corners(ShotGeometry.options[0].box)
        assertFalse(ShotGeometry.valid(valid.reversed()))
        assertFalse(ShotGeometry.aimReady(emptyList()))
        assertNull(ShotGeometry.desiredZoom(1f, valid.map { ScenePoint(Float.NaN, it.y) }, 1f, 8f))
        assertFalse(ShotGeometry.framingReady(listOf(valid[0], valid[2], valid[1], valid[3])))
    }
    @Test fun `alignment needs a sustained fresh match and resets after tracking loss`() {
        val hold = AlignmentHold()
        assertFalse(hold.update(true, 1000)); assertFalse(hold.update(true, 1600))
        assertTrue(hold.update(true, 1650)); assertFalse(hold.update(false, 1700))
        assertFalse(hold.update(true, 1800)); assertTrue(hold.update(true, 2450))
        hold.reset(); assertFalse(hold.update(true, 3000))
    }
    @Test fun `model crops stay inside the image when the subject is at an edge`() {
        val box = ShotGeometry.cropFromModel(.95f, .03f, 1.8f)
        assertEquals(1f, box.right, .0001f)
        assertEquals(0f, box.top, .0001f)
        assertEquals(box.right - box.left, box.bottom - box.top, .0001f)
    }
    @Test fun `model geometry rejects nonfinite and out of range values`() {
        assertThrows(IllegalArgumentException::class.java) { ShotGeometry.cropFromModel(Float.NaN, .5f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { ShotGeometry.cropFromModel(.5f, Float.POSITIVE_INFINITY, 1f) }
        assertThrows(IllegalArgumentException::class.java) { ShotGeometry.cropFromModel(.5f, .5f, 2f) }
        assertThrows(IllegalArgumentException::class.java) { ShotGeometry.cropFromModel(-.1f, .5f, 1f) }
    }
}
