package dev.cameracompositor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.abs

/** A modest SDR curve for underexposed JPEGs, with exact black and highlight endpoints. */
object ShadowTone {
    fun curve(median: Int): IntArray? {
        if (median >= 105) return null
        val strength = ((105 - median) * .32f).coerceIn(0f, 19f)
        return IntArray(256) { value ->
            val shadow = value / 190f
            val weight = if (shadow < 1f) 6.75f * shadow * (1 - shadow) * (1 - shadow) else 0f
            (value + strength * weight + .5f).toInt().coerceIn(0, 255)
        }
    }

    fun luminance(rgb: Int) = (54 * (rgb shr 16 and 255) + 183 * (rgb shr 8 and 255) + 19 * (rgb and 255)) shr 8

    fun finish(pixel: Int, left: Int, right: Int, above: Int, below: Int, curve: IntArray): Int {
        val y = luminance(pixel)
        val denoise = y < 110 && similar(pixel, y, left) && similar(pixel, y, right) &&
            similar(pixel, y, above) && similar(pixel, y, below)
        val color = if (denoise) {
            val rb = ((4 * (pixel and 0xff00ff) + (left and 0xff00ff) + (right and 0xff00ff) +
                (above and 0xff00ff) + (below and 0xff00ff)) shr 3) and 0xff00ff
            val g = ((4 * (pixel and 0xff00) + (left and 0xff00) + (right and 0xff00) +
                (above and 0xff00) + (below and 0xff00)) shr 3) and 0xff00
            rb or g
        } else pixel
        val r = color shr 16 and 255
        val g = color shr 8 and 255
        val b = color and 255
        val luma = luminance(color)
        val delta = minOf(curve[luma] - luma, 255 - maxOf(r, g, b))
        return 0xff000000.toInt() or ((r + delta) shl 16) or ((g + delta) shl 8) or (b + delta)
    }

    private fun similar(base: Int, y: Int, other: Int) = abs(y - luminance(other)) <= 12 &&
        abs((base shr 16 and 255) - (other shr 16 and 255)) <= 19 &&
        abs((base shr 8 and 255) - (other shr 8 and 255)) <= 19 &&
        abs((base and 255) - (other and 255)) <= 19
}

/** Finish only a JPEG that can be processed safely at its original size. Called on a photo worker. */
object PhotoFinisher {
    private val captureTags = listOf(ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_COLOR_SPACE, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION)

    fun enhance(original: File, width: Int, height: Int): File? {
        val pixels = width.toLong() * height
        val runtime = Runtime.getRuntime()
        val headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        if (width < 3 || height < 3 || pixels > 16_000_000 || pixels * 8 + 28_000_000 > headroom) return null
        val curve = ShadowTone.curve(median(original, width, height) ?: return null) ?: return null
        val options = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val photo = BitmapFactory.decodeFile(original.path, options) ?: return null
        try {
            if (photo.colorSpace?.isSrgb != true || (Build.VERSION.SDK_INT >= 34 && photo.hasGainmap())) return null
            finishRows(photo, curve)
            val processed = File.createTempFile("finished-", ".jpg", original.parentFile)
            try {
                processed.outputStream().use { check(photo.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
                val from = ExifInterface(original)
                val to = ExifInterface(processed)
                for (tag in captureTags) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
                from.latLong?.let { to.setLatLong(it[0], it[1]) }
                to.saveAttributes()
                return processed
            } catch (error: Exception) { processed.delete(); throw error }
            catch (error: OutOfMemoryError) { processed.delete(); throw error }
        } finally { photo.recycle() }
    }

    private fun median(file: File, width: Int, height: Int): Int? {
        val sample = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((maxOf(width, height) / 384).coerceAtLeast(1))
        }
        val reduced = BitmapFactory.decodeFile(file.path, sample) ?: return null
        return try {
            val row = IntArray(reduced.width)
            val histogram = IntArray(256)
            for (y in 0 until reduced.height) {
                reduced.getPixels(row, 0, reduced.width, 0, y, reduced.width, 1)
                for (pixel in row) histogram[ShadowTone.luminance(pixel)]++
            }
            var seen = 0
            histogram.indices.first { value -> seen += histogram[value]; seen * 2 >= reduced.width * reduced.height }
        } finally { reduced.recycle() }
    }

    private fun finishRows(bitmap: Bitmap, curve: IntArray) {
        val width = bitmap.width
        val height = bitmap.height
        var above = IntArray(width)
        var current = IntArray(width)
        var below = IntArray(width)
        val output = IntArray(width)
        bitmap.getPixels(current, 0, width, 0, 0, width, 1)
        current.copyInto(above)
        for (y in 0 until height) {
            if (y + 1 < height) bitmap.getPixels(below, 0, width, 0, y + 1, width, 1) else current.copyInto(below)
            for (x in 0 until width) {
                output[x] = ShadowTone.finish(current[x], current[(x - 1).coerceAtLeast(0)],
                    current[(x + 1).coerceAtMost(width - 1)], above[x], below[x], curve)
            }
            bitmap.setPixels(output, 0, width, 0, y, width, 1)
            val reuse = above
            above = current; current = below; below = reuse
        }
    }
}
