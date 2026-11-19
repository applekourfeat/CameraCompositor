package dev.cameracompositor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class GuideOverlay(context: Context, attrs: AttributeSet) : View(context, attrs) {
    var frame: List<ScenePoint>? = null
    var fresh = false
    var aligned = false
    var level: CameraLevel? = null
        set(value) {
            if (field != value) { field = value; invalidate() }
        }
    var aimPoint: ScenePoint? = null
    var targetPreview: Bitmap? = null
    var targetVisible = true
    var aimTolerance = ShotGeometry.DEFAULT_AIM_TOLERANCE
    var subjectPin: ScenePoint? = null
        set(value) { field = value; invalidate() }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val thumbnailRect = RectF()
    private val arrowOffsets = floatArrayOf(-.65f, .65f)
    private val density = resources.displayMetrics.density
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (targetVisible) drawTargetPreview(canvas)
        drawSubjectPin(canvas)
        drawLevel(canvas)
        val aim = aimPoint
        val points = frame
        if (aim == null && points == null) return
        pen.style = Paint.Style.STROKE; pen.strokeWidth = 2 * density
        pen.color = if (aligned && fresh) Color.rgb(125, 220, 150) else Color.WHITE
        pen.alpha = if (fresh) 240 else 85
        if (aim != null) {
            drawDirection(canvas, aim, !NextView.arrived(aim))
            return
        }
        if (points == null) return
        path.reset()
        path.apply {
            moveTo(points[0].x * width, points[0].y * height)
            for (index in 1..3) lineTo(points[index].x * width, points[index].y * height)
            close()
        }
        canvas.drawPath(path, pen)
        if (fresh) drawDirection(canvas, ShotGeometry.center(points), !ShotGeometry.aimReady(points, aimTolerance))
    }

    private fun drawLevel(canvas: Canvas) {
        val reading = level ?: return
        val cx = width * .5f
        val cy = height * .64f
        val d = density
        canvas.save()
        canvas.translate(cx, cy)
        if (reading is CameraLevel.Horizon && reading.verticalOnDisplay) canvas.rotate(90f)
        pen.style = Paint.Style.STROKE
        // A dark under-stroke keeps the thin instrument visible against bright sky.
        for (pass in 0..1) {
            val outline = pass == 0
            pen.strokeWidth = (if (outline) 4f else 2f) * d
            pen.color = if (outline) Color.BLACK else if (reading.aligned) Color.rgb(125, 220, 150) else Color.WHITE
            if (reading is CameraLevel.Horizon) {
                canvas.drawLine(-44*d, 0f, -26*d, 0f, pen)
                canvas.drawLine(26*d, 0f, 44*d, 0f, pen)
                canvas.save()
                canvas.rotate(reading.degrees)
                canvas.drawLine(-24*d, 0f, 24*d, 0f, pen)
                canvas.restore()
            } else if (reading is CameraLevel.Overhead) {
                canvas.drawCircle(0f, 0f, 9*d, pen)
                canvas.drawCircle(reading.x*240*d, reading.y*240*d, 4*d, pen)
            }
        }
        canvas.restore()
    }

    private fun drawDirection(canvas: Canvas, point: ScenePoint, arrow: Boolean) {
        val cx = width * .5f
        val cy = height * .5f
        canvas.drawCircle(cx, cy, 7 * density, pen)
        if (!arrow) return
        val angle = atan2((point.y - .5f) * height, (point.x - .5f) * width)
        val length = 42 * density
        val x = cx + cos(angle) * length
        val y = cy + sin(angle) * length
        canvas.drawLine(cx, cy, x, y, pen)
        for (offset in arrowOffsets) canvas.drawLine(x, y,
            x - cos(angle + offset) * 12 * density, y - sin(angle + offset) * 12 * density, pen)
    }

    private fun drawSubjectPin(canvas: Canvas) {
        val point = subjectPin ?: return
        pen.style = Paint.Style.STROKE
        pen.color = Color.WHITE
        pen.strokeWidth = 2 * density
        canvas.drawCircle(point.x * width, point.y * height, 14 * density, pen)
        canvas.drawCircle(point.x * width, point.y * height, 2 * density, pen)
    }

    private fun drawTargetPreview(canvas: Canvas) {
        targetPreview?.let { bitmap ->
            val w = 76 * density; val h = w * 4 / 3
            thumbnailRect.set(width - w - 12 * density, 12 * density, width - 12 * density, h + 12 * density)
            pen.style = Paint.Style.FILL; pen.color = Color.BLACK; pen.alpha = 230
            canvas.drawRect(thumbnailRect, pen); canvas.drawBitmap(bitmap, null, thumbnailRect, pen)
            pen.style = Paint.Style.STROKE; pen.color = Color.WHITE; canvas.drawRect(thumbnailRect, pen)
        }
    }
}
