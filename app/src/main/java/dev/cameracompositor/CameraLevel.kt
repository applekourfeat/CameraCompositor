package dev.cameracompositor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/** World up projected onto the preview. Close to vertical aim, use a two-axis bubble. */
sealed interface CameraLevel {
    val aligned: Boolean

    data class Horizon(val degrees: Float, val verticalOnDisplay: Boolean = false) : CameraLevel {
        override val aligned get() = abs(degrees) <= 1.5f
    }

    data class Overhead(val x: Float, val y: Float) : CameraLevel {
        override val aligned get() = hypot(x, y) <= .035f
    }

    companion object {
        fun fromUp(x: Float, y: Float): CameraLevel? {
            val projection = hypot(x, y)
            if (!projection.isFinite() || projection > 1.001f) return null
            if (projection < .12f) return Overhead(x, -y)
            val angle = Math.toDegrees(atan2(x, y).toDouble()).toFloat()
            return Horizon(angle - 90f * round(angle / 90f), abs(x) > abs(y))
        }

        /** A world-level rectangle in the tilted reference image, using square pixels. */
        fun corners(box: SubjectBox, level: CameraLevel?, aspect: Float): List<ScenePoint> {
            val corners = ShotGeometry.corners(box)
            if (level !is Horizon || level.degrees == 0f) return corners
            val center = ShotGeometry.center(corners)
            val angle = Math.toRadians(level.degrees.toDouble())
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            return corners.map {
                val x = (it.x - center.x) * aspect
                val y = it.y - center.y
                ScenePoint(center.x + (c*x - s*y) / aspect, center.y + s*x + c*y)
            }
        }
    }
}
