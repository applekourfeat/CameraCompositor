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

/** Relative inverse depth from RGB. Increasing values indicate nearer surfaces within one view. */
class RelativeDepth(val columns: Int, val rows: Int, val values: FloatArray) {
    init {
        require(columns > 0 && rows > 0 && values.size == columns * rows)
        require(values.all { it.isFinite() && it in 0f..1f })
    }

    fun at(x: Float, y: Float): Float {
        val column = (x * columns).toInt().coerceIn(0, columns - 1)
        val row = (y * rows).toInt().coerceIn(0, rows - 1)
        return values[row * columns + column]
    }

    fun grid8x8(): List<Float> = List(64) { index -> at((index % 8 + .5f) / 8, (index / 8 + .5f) / 8) }

    companion object {
        fun fromOutput(pixels: FloatArray, width: Int, height: Int): RelativeDepth? {
            val columns = 24
            val rows = 32
            if (width < columns || height < rows || pixels.size.toLong() != width.toLong() * height || pixels.any { !it.isFinite() }) return null
            val values = FloatArray(columns * rows) { index ->
                val x = index % columns
                val y = index / columns
                var sum = 0.0
                val left = x * width / columns
                val top = y * height / rows
                val right = (x + 1) * width / columns
                val bottom = (y + 1) * height / rows
                for (r in top until bottom) for (c in left until right) sum += pixels[r * width + c]
                (sum / ((right - left) * (bottom - top))).toFloat()
            }
            val sorted = values.sortedArray()
            val low = sorted[sorted.size / 20]
            val range = sorted[sorted.lastIndex - sorted.size / 20] - low
            if (!range.isFinite() || range <= 1e-4f) return null
            for (i in values.indices) values[i] = ((values[i] - low) / range).coerceIn(0f, 1f)
            return RelativeDepth(columns, rows, values)
        }
    }
}

/** Depth estimates have arbitrary scale. Require matching order in the registered overlap. */
object DepthAgreement {
    private val grid = List(63) { i -> ScenePoint((i % 7 + 1f) / 8, (i / 7 + 1f) / 10) }

    fun corroborated(map: EnvironmentMap, estimates: Map<Int, RelativeDepth>): Map<Int, RelativeDepth> {
        val trusted = mutableSetOf<Int>()
        val ids = estimates.keys.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val from = ids[i]
            val to = ids[j]
            val overlap = map.projectPoints(from, to, grid) ?: continue
            val a = estimates.getValue(from)
            val b = estimates.getValue(to)
            val samples = overlap.mapIndexedNotNull { k, point ->
                if (point.x !in .08f .. .92f || point.y !in .08f .. .92f) null
                else a.at(grid[k].x, grid[k].y) to b.at(point.x, point.y)
            }
            if (samples.size < 28) continue
            var compared = 0
            var agreed = 0
            for (k in samples.indices) for (l in k + 1 until samples.size) {
                val deltaA = samples[k].first - samples[l].first
                val deltaB = samples[k].second - samples[l].second
                if (abs(deltaA) < .16f || abs(deltaB) < .16f) continue
                compared++
                if (deltaA * deltaB > 0) agreed++
            }
            if (compared >= 60 && agreed >= compared * .80f) {
                trusted.add(from)
                trusted.add(to)
            }
        }
        return estimates.filterKeys { it in trusted }
    }
}

/** Model-executor only. A CPU model consumes selected frames, leaving preview and capture separate. */
class SoftwareDepth(private val context: Context) : AutoCloseable {
    companion object {
        private const val MODEL = "zipdepth-192x256.onnx"
        private const val WIDTH = 192
        private const val HEIGHT = 256
    }

    private val environment by lazy { OrtEnvironment.getEnvironment().also { it.setTelemetry(false) } }
    private var session: OrtSession? = null
    private var unavailable = false

    fun estimate(image: Bitmap): RelativeDepth? {
        if (unavailable) return null
        return try {
            val model = session ?: openModel().also { session = it }
            val count = WIDTH * HEIGHT
            val input = FloatArray(count * 3)
            val rgb = IntArray(count)
            val resized = Bitmap.createScaledBitmap(image, WIDTH, HEIGHT, true)
            try {
                resized.getPixels(rgb, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            } finally {
                if (resized !== image) resized.recycle()
            }
            for (i in rgb.indices) {
                input[i] = (rgb[i] shr 16 and 255) / 255f
                input[count + i] = (rgb[i] shr 8 and 255) / 255f
                input[count * 2 + i] = (rgb[i] and 255) / 255f
            }
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, HEIGHT.toLong(), WIDTH.toLong())).use { tensor ->
                model.run(mapOf("image" to tensor)).use { output ->
                    val depth = (output[0] as OnnxTensor).floatBuffer
                    val values = FloatArray(depth.remaining()).also { depth.get(it) }
                    RelativeDepth.fromOutput(values, WIDTH, HEIGHT)
                }
            }
        } catch (_: Exception) {
            // The same local planner can use image evidence on a build or device without this model.
            unavailable = true
            close()
            null
        } catch (_: LinkageError) {
            unavailable = true
            null
        }
    }

    private fun openModel(): OrtSession = context.assets.openFd(MODEL).use { asset ->
        FileInputStream(asset.fileDescriptor).use { stream ->
            val mapped = stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                environment.createSession(mapped, options)
            }
        }
    }

    override fun close() {
        session?.close()
        session = null
    }
}
