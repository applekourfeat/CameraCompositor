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
    var targetPreview: Bitmap? = null
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val thumbnailRect = RectF()
    private val arrowOffsets = floatArrayOf(-.65f, .65f)
    private val density = resources.displayMetrics.density
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTargetPreview(canvas)
        val points = frame ?: return
        pen.style = Paint.Style.STROKE; pen.strokeWidth = 2 * density
        pen.color = if (aligned && fresh) Color.rgb(125, 220, 150) else Color.WHITE
        pen.alpha = if (fresh) 240 else 85
        path.reset()
        path.apply {
            moveTo(points[0].x * width, points[0].y * height)
            for (index in 1..3) lineTo(points[index].x * width, points[index].y * height)
            close()
        }
        canvas.drawPath(path, pen)
        if (fresh) {
            val center = ShotGeometry.center(points)
            val cx = width * .5f; val cy = height * .5f
            canvas.drawCircle(cx, cy, 7 * density, pen)
            if (!ShotGeometry.aimReady(points)) {
                val angle = atan2((center.y - .5f) * height, (center.x - .5f) * width)
                val length = 42 * density
                val x = cx + cos(angle) * length; val y = cy + sin(angle) * length
                canvas.drawLine(cx, cy, x, y, pen)
                for (offset in arrowOffsets) canvas.drawLine(x, y,
                    x - cos(angle + offset) * 12 * density, y - sin(angle + offset) * 12 * density, pen)
            }
        }
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
