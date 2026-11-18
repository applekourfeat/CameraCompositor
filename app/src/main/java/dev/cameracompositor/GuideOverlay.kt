package dev.cameracompositor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GuideOverlay(context: Context, attrs: AttributeSet) : View(context, attrs) {
    var subject: SubjectBox? = null
    var targetX = 2f / 3f
    var aligned = false
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pen.strokeWidth = density
        pen.color = Color.argb(90, 255, 255, 255)
        for (fraction in listOf(1f / 3f, 2f / 3f)) {
            canvas.drawLine(width * fraction, 0f, width * fraction, height.toFloat(), pen)
            canvas.drawLine(0f, height * fraction, width.toFloat(), height * fraction, pen)
        }
        pen.strokeWidth = 2f * density
        pen.color = if (aligned) Color.rgb(120, 225, 150) else Color.WHITE
        canvas.drawCircle(width * targetX, height * 0.5f, 12 * density, pen)
        subject?.let {
            canvas.drawRect(it.left * width, it.top * height, it.right * width, it.bottom * height, pen)
        }
    }
}
