package dev.cameracompositor

import kotlin.math.abs
import kotlin.math.hypot

data class SubjectBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

class AlignmentHold(private val durationMs: Long = 650) {
    private var since: Long? = null
    fun update(aligned: Boolean, nowMs: Long): Boolean {
        if (!aligned) { reset(); return false }
        val start = since ?: nowMs.also { since = it }
        return nowMs - start >= durationMs
    }
    fun reset() { since = null }
}

data class ScenePoint(val x: Float, val y: Float)
data class CropOption(val id: Int, val box: SubjectBox) {
    val zoom get() = 1f / (box.right - box.left)
}

/** Known crop candidates let the model choose a framing without inventing pixel coordinates. */
object ShotGeometry {
    fun cropFromModel(x: Float, y: Float, zoom: Float): SubjectBox {
        require(x.isFinite() && y.isFinite() && zoom.isFinite() && x in 0f..1f && y in 0f..1f && zoom in 1f..1.8f) {
            "The scene service proposed an invalid framing. Try another scan."
        }
        val half = .5f / zoom
        val cx = x.coerceIn(half, 1f - half); val cy = y.coerceIn(half, 1f - half)
        return SubjectBox(cx - half, cy - half, cx + half, cy + half)
    }
    val options: List<CropOption> = buildList {
        add(CropOption(0, SubjectBox(0f, 0f, 1f, 1f)))
        fun addCrop(zoom: Float, x: Float, y: Float) {
            val half = .5f / zoom
            add(CropOption(size, SubjectBox(x - half, y - half, x + half, y + half)))
        }
        addCrop(1.35f, .5f, .5f)
        addCrop(1.6f, .34f, .34f)
        addCrop(1.6f, .66f, .34f)
        addCrop(1.6f, .34f, .66f)
        addCrop(1.6f, .66f, .66f)
        addCrop(1.8f, .5f, .5f)
        addCrop(1.6f, .5f, .32f)
        addCrop(1.6f, .5f, .68f)
    }

    fun corners(box: SubjectBox) = listOf(ScenePoint(box.left, box.top), ScenePoint(box.right, box.top),
        ScenePoint(box.right, box.bottom), ScenePoint(box.left, box.bottom))

    fun center(points: List<ScenePoint>): ScenePoint {
        var x = 0f; var y = 0f
        for (point in points) { x += point.x; y += point.y }
        return ScenePoint(x / points.size, y / points.size)
    }

    fun width(points: List<ScenePoint>): Float = (hypot(points[1].x - points[0].x, points[1].y - points[0].y) +
        hypot(points[2].x - points[3].x, points[2].y - points[3].y)) / 2f

    fun aimReady(points: List<ScenePoint>): Boolean {
        if (!valid(points)) return false
        val c = center(points)
        return abs(c.x - .5f) < .045f && abs(c.y - .5f) < .045f
    }

    fun framingReady(points: List<ScenePoint>) = aimReady(points) && abs(width(points) - 1f) < .09f &&
        abs(points[1].y - points[0].y) < .07f

    fun desiredZoom(current: Float, points: List<ScenePoint>, baseline: Float, deviceMax: Float, relativeLimit: Float = 1.8f): Float? {
        require(relativeLimit.isFinite() && relativeLimit in 1f..3.2f)
        if (!aimReady(points)) return null
        val scale = width(points)
        if (!scale.isFinite() || scale < .15f) return null
        return (current / scale).coerceIn(baseline, minOf(deviceMax, baseline * relativeLimit).coerceAtLeast(baseline))
    }

    fun valid(points: List<ScenePoint>): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || abs(it.x) > 5 || abs(it.y) > 5 }) return false
        val cross = points.indices.map { i ->
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return cross.all { it > .005f } && width(points) in .15f..4f
    }
}

data class ModelChoice(val cropId: Int, val explanation: String, val customCrop: SubjectBox? = null)
