package dev.cameracompositor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.FileInputStream
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

data class DetectedSubject(val box: SubjectBox, val category: Int, val confidence: Float)

/** Converts the pinned NanoDet output's distribution of edge distances into subject boxes. */
object SubjectDetection {
    const val SIZE = 416
    private const val CLASSES = 80
    private const val BINS = 8
    val strides = listOf(8, 16, 32)

    fun decode(scores: FloatBuffer, distances: FloatBuffer, stride: Int, width: Int, height: Int): List<DetectedSubject> {
        val side = SIZE / stride
        require(scores.remaining() == side * side * CLASSES && distances.remaining() == side * side * 4 * BINS)
        val padX = (SIZE - width) / 2
        val padY = (SIZE - height) / 2
        val detections = mutableListOf<DetectedSubject>()
        for (anchor in 0 until side * side) {
            var category = 0
            var score = .35f
            val first = anchor * CLASSES
            for (index in 0 until CLASSES) {
                val probability = scores[first + index]
                if (probability > score) { score = probability; category = index }
            }
            if (score <= .35f || !score.isFinite()) continue
            val x = anchor % side * stride + (stride - 1) * .5f - padX
            val y = anchor / side * stride + (stride - 1) * .5f - padY
            if (x !in 0f..width.toFloat() || y !in 0f..height.toFloat()) continue
            val offset = anchor * 4 * BINS
            fun edge(sideIndex: Int) = distance(distances, offset + sideIndex * BINS) * stride
            val box = SubjectBox(((x - edge(0)) / width).coerceIn(0f, 1f), ((y - edge(1)) / height).coerceIn(0f, 1f),
                ((x + edge(2)) / width).coerceIn(0f, 1f), ((y + edge(3)) / height).coerceIn(0f, 1f))
            if (area(box) in .008f .. .76f && box.right - box.left > .065f && box.bottom - box.top > .08f) {
                detections += DetectedSubject(box, category, score)
            }
        }
        return detections
    }

    private fun distance(values: FloatBuffer, offset: Int): Float {
        val highest = (0 until BINS).maxOf { values[offset + it] }
        var weight = 0f
        var total = 0f
        for (i in 0 until BINS) {
            val likelihood = exp(values[offset + i] - highest)
            weight += i * likelihood
            total += likelihood
        }
        return weight / total
    }

    fun mainSubject(detections: List<DetectedSubject>): DetectedSubject? {
        val distinct = mutableListOf<DetectedSubject>()
        for (candidate in detections.sortedByDescending { it.confidence }) {
            val size = area(candidate.box)
            if (distinct.none { subject ->
                    val overlap = intersection(candidate.box, subject.box)
                    overlap / (size + area(subject.box) - overlap) > .55f
                }) distinct.add(candidate)
            if (distinct.size == 12) break
        }
        fun priority(subject: DetectedSubject): Float {
            val b = subject.box
            val distance = hypot((b.left + b.right) * .5f - .5f, (b.top + b.bottom) * .5f - .45f)
            val kind = when (subject.category) {
                0, in 14..23 -> 1.2f // people and animals
                57, 59, 60 -> .75f // common room backgrounds
                else -> 1f
            }
            return subject.confidence * (1f - .8f * distance) * (.5f + sqrt(area(b).coerceAtMost(.25f))) * kind
        }
        val best = distinct.maxByOrNull(::priority)?.takeIf { it.confidence >= .39f } ?: return null
        // Keep a nearby pair together when the visible subjects are both plausible.
        val partner = distinct.firstOrNull { other ->
            other !== best && other.category == best.category && other.confidence >= .41f &&
                priority(other) >= priority(best) * .75f &&
                abs((other.box.top + other.box.bottom - best.box.top - best.box.bottom) * .5f) < .28f
        } ?: return best
        val group = SubjectBox(minOf(best.box.left, partner.box.left), minOf(best.box.top, partner.box.top),
            maxOf(best.box.right, partner.box.right), maxOf(best.box.bottom, partner.box.bottom))
        return if (area(group) < .68f) best.copy(box = group, confidence = minOf(best.confidence, partner.confidence)) else best
    }

    private fun intersection(a: SubjectBox, b: SubjectBox) =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)

    private fun area(b: SubjectBox) = (b.right - b.left) * (b.bottom - b.top)
}

/** One bounded inference on the latest observed image. The regular planner handles missing detections. */
class SceneSubjectDetector(private val context: Context) : AutoCloseable {
    private val runtime by lazy { OrtEnvironment.getEnvironment().also { it.setTelemetry(false) } }
    private var session: OrtSession? = null
    private var unavailable = false

    fun detect(image: Bitmap): DetectedSubject? {
        if (unavailable) return null
        return try {
            val model = session ?: open().also { session = it }
            val size = SubjectDetection.SIZE
            val scale = size.toFloat() / maxOf(image.width, image.height)
            val width = (image.width * scale).toInt().coerceIn(1, size)
            val height = (image.height * scale).toInt().coerceIn(1, size)
            val pixels = IntArray(width * height)
            val scaled = Bitmap.createScaledBitmap(image, width, height, true)
            try { scaled.getPixels(pixels, 0, width, 0, 0, width, height) }
            finally { if (scaled !== image) scaled.recycle() }
            val area = size * size
            val grid = FloatArray(area * 3)
            val mean = floatArrayOf(103.53f, 116.28f, 123.675f)
            val std = floatArrayOf(57.375f, 57.12f, 58.395f)
            for (channel in 0..2) {
                val start = area * channel
                grid.fill(-mean[channel] / std[channel], start, start + area)
                for (y in 0 until height) for (x in 0 until width) {
                    val target = start + (y + (size - height) / 2) * size + x + (size - width) / 2
                    val value = pixels[y * width + x] shr ((2 - channel) * 8) and 255
                    grid[target] = (value - mean[channel]) / std[channel]
                }
            }
            OnnxTensor.createTensor(runtime, FloatBuffer.wrap(grid), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
                model.run(mapOf("input.1" to input)).use { output ->
                    val scoreIds = listOf("792", "814", "836")
                    val boxIds = listOf("795", "817", "839")
                    val found = SubjectDetection.strides.flatMapIndexed { index, stride ->
                        val scores = (output.get(scoreIds[index]).get() as OnnxTensor).floatBuffer
                        val boxes = (output.get(boxIds[index]).get() as OnnxTensor).floatBuffer
                        SubjectDetection.decode(scores, boxes, stride, width, height)
                    }
                    SubjectDetection.mainSubject(found)
                }
            }
        } catch (_: Exception) {
            unavailable = true
            runCatching { close() }
            null
        } catch (_: LinkageError) {
            unavailable = true
            null
        }
    }

    private fun open(): OrtSession = context.assets.openFd("nanodet-416.onnx").use { asset ->
        FileInputStream(asset.fileDescriptor).use { stream ->
            val model = stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                runtime.createSession(model, options)
            }
        }
    }

    override fun close() { session?.close(); session = null }
}
