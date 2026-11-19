package dev.cameracompositor

/** Camera axes in Android's local world frame. Camera points forward along screen -Z. */
data class ViewAttitude(val rotation: FloatArray, val timestampNs: Long, val fx: Float, val fy: Float)

/** Short-lived orientation estimate to keep the guide visible between verified image matches. */
object RotationGuide {
    fun estimate(points: List<ScenePoint>, reference: ViewAttitude, current: ViewAttitude): List<ScenePoint>? {
        if (reference.rotation.size != 9 || current.rotation.size != 9) return null
        if (current.timestampNs - reference.timestampNs !in 0L..4_500_000_000L ||
            listOf(reference.fx, reference.fy, current.fx, current.fy).any { !it.isFinite() || it !in .3f..8f } ||
            current.fx / reference.fx !in .75f..1.34f) return null
        val a = reference.rotation
        val b = current.rotation
        val forwardDot = a[2] * b[2] + a[5] * b[5] + a[8] * b[8]
        if (!forwardDot.isFinite() || forwardDot < .62f) return null
        val projected = points.map { point ->
            val x = (point.x - .5f) / reference.fx
            val y = (.5f - point.y) / reference.fy
            val worldX = a[0] * x + a[1] * y - a[2]
            val worldY = a[3] * x + a[4] * y - a[5]
            val worldZ = a[6] * x + a[7] * y - a[8]
            val viewX = b[0] * worldX + b[3] * worldY + b[6] * worldZ
            val viewY = b[1] * worldX + b[4] * worldY + b[7] * worldZ
            val depth = -(b[2] * worldX + b[5] * worldY + b[8] * worldZ)
            if (!depth.isFinite() || depth < .23f) return null
            ScenePoint(.5f + current.fx * viewX / depth, .5f - current.fy * viewY / depth)
        }
        return projected.takeIf(ShotGeometry::valid)
    }
}
