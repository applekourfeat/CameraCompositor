package dev.cameracompositor

import android.graphics.*

/** Manual fallback lets the tracking/zoom guide work without a network request. */
object CropPreviews {
    fun contactSheet(frame: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(576, 768, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
        val background = Paint().apply { color = Color.BLACK }
        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (option in ShotGeometry.options) {
            val x = (option.id % 3) * 192f; val y = (option.id / 3) * 256f
            val b = option.box
            val source = Rect((b.left * frame.width).toInt(), (b.top * frame.height).toInt(),
                (b.right * frame.width).toInt(), (b.bottom * frame.height).toInt())
            canvas.drawBitmap(frame, source, RectF(x + 2, y + 2, x + 190, y + 254), imagePaint)
            canvas.drawRect(x + 3, y + 3, x + 31, y + 30, background)
            canvas.drawText(option.id.toString(), x + 10, y + 25, text)
        }
        return output
    }
}
