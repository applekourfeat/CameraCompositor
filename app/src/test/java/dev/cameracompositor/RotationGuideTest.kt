package dev.cameracompositor

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class RotationGuideTest {
    private val corners = ShotGeometry.corners(SubjectBox(.14f, .22f, .86f, .94f))
    private val reference = attitude(0f)

    private fun attitude(yawRight: Float, time: Long = 1L, zoom: Float = 1f): ViewAttitude {
        val c = cos(yawRight)
        val s = sin(yawRight)
        return ViewAttitude(floatArrayOf(c, 0f, -s, 0f, 1f, 0f, s, 0f, c), time, .9f * zoom, .675f * zoom)
    }

    @Test fun `turning right moves the estimate left in viewfinder coordinates`() {
        val estimate = RotationGuide.estimate(corners, reference, attitude(.16f, 10))!!
        val center = ShotGeometry.center(estimate)
        assertTrue(center.x in .30f.. .40f)
        assertEquals(.58f, center.y, .02f)
        val left = RotationGuide.estimate(corners, reference, attitude(-.16f, 10))!!
        assertEquals(1f, center.x + ShotGeometry.center(left).x, .005f)
    }

    @Test fun `old samples a large turn and a lens jump cannot guide`() {
        assertNull(RotationGuide.estimate(corners, reference, attitude(.1f, 4_600_000_010L)))
        assertNull(RotationGuide.estimate(corners, reference, attitude(1.2f, 10)))
        assertNull(RotationGuide.estimate(corners, reference, attitude(.1f, 10, 2f)))
    }
}
