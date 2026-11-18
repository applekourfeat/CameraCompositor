package dev.cameracompositor

import kotlin.math.abs

/** Projective registration in normalized viewfinder coordinates. */
data class ImageProjection(val coefficients: List<Float>) {
    init {
        require(coefficients.size == 9 && coefficients.all(Float::isFinite))
    }

    fun project(point: ScenePoint): ScenePoint? {
        val (x, y) = point
        val m = coefficients
        val scale = m[6] * x + m[7] * y + m[8]
        if (abs(scale) < .00001f) return null
        val projected = ScenePoint(
            (m[0] * x + m[1] * y + m[2]) / scale,
            (m[3] * x + m[4] * y + m[5]) / scale
        )
        return projected.takeIf { it.x.isFinite() && it.y.isFinite() && abs(it.x) < 8 && abs(it.y) < 8 }
    }

    fun project(box: SubjectBox): SubjectBox? {
        val points = ShotGeometry.corners(box).map { project(it) ?: return null }
        return SubjectBox(points.minOf { it.x }, points.minOf { it.y },
            points.maxOf { it.x }, points.maxOf { it.y })
    }
}
