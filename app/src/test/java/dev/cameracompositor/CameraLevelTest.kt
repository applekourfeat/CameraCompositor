package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class CameraLevelTest {
    private fun reading(tilt: Double, projection: Double = 1.0): CameraLevel.Horizon {
        val radians = Math.toRadians(tilt)
        return CameraLevel.fromUp((projection * sin(radians)).toFloat(),
            (projection * cos(radians)).toFloat()) as CameraLevel.Horizon
    }

    @Test fun horizonWorksAtEachShootingOrientationAndWhileLookingUp() {
        for (orientation in listOf(-180, -90, 0, 90, 180)) {
            assertTrue(reading(orientation + 1.0, .4).aligned)
            assertEquals(-6f, reading(orientation - 6.0, .4).degrees, .001f)
            assertFalse(reading(orientation + 2.0).aligned)
        }
    }

    @Test fun overheadUsesBothAxesAndInvalidReadingsStayUnavailable() {
        assertTrue(CameraLevel.fromUp(.02f, -.02f)!!.aligned)
        assertFalse(CameraLevel.fromUp(.03f, .03f)!!.aligned)
        assertTrue(CameraLevel.fromUp(.04f, .01f) is CameraLevel.Overhead)
        assertNull(CameraLevel.fromUp(Float.NaN, .1f))
    }

    @Test fun aTiltedObservationSeedsAWorldLevelFrameInPortraitPixels() {
        val box = SubjectBox(.1f, .2f, .7f, .8f)
        val corners = CameraLevel.corners(box, reading(-8.0), .75f)
        val dx = (corners[1].x - corners[0].x) * .75
        val dy = (corners[1].y - corners[0].y).toDouble()
        assertEquals(-8.0, Math.toDegrees(atan2(dy, dx)), .001)
        assertEquals(.4f, ShotGeometry.center(corners).x, .0001f)
        assertEquals(.5f, ShotGeometry.center(corners).y, .0001f)
        assertEquals(ShotGeometry.corners(box), CameraLevel.corners(box, null, .75f))
    }

    @Test fun matchingTheBoxWhileTiltedCannotCompleteCountdown() {
        val box = ShotGeometry.corners(SubjectBox(0f, 0f, 1f, 1f))
        val shutter = AutomaticShutter()
        fun tick(time: Long, tilt: Double) = shutter.update(time, time, box,
            ShotGeometry.framingReady(box) && reading(tilt).aligned)
        for (time in 0L..1900L step 100) tick(time, .5)
        assertEquals(ShutterUpdate.Counting(2), tick(2000, 1.0))
        for (time in 2100L..6000L step 100) assertEquals(ShutterUpdate.Waiting, tick(time, 3.0))
        for (time in 6100L..9500L step 100) assertNotEquals(ShutterUpdate.Capture, tick(time, .5))
        assertEquals(ShutterUpdate.Capture, tick(9600, .5))
    }
}
