package dev.cameracompositor

import android.graphics.*

/** Manual fallback lets the tracking/zoom guide work without a network request. */
object CropPreviews {
    fun thumbnail(frame: Bitmap, corners: List<ScenePoint>): Bitmap {
        val output = Bitmap.createBitmap(180, 240, Bitmap.Config.ARGB_8888)
        val source = FloatArray(6)
        for (i in 0..2) {
            source[2*i] = corners[i].x * frame.width
            source[2*i+1] = corners[i].y * frame.height
        }
        val transform = Matrix().apply {
            setPolyToPoly(source, 0, floatArrayOf(0f, 0f, 180f, 0f, 180f, 240f), 0, 3)
        }
        Canvas(output).drawBitmap(frame, transform, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

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
