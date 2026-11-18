package dev.cameracompositor

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

data class LocalResearchResult(val crop: CropOption, val scores: List<Float>, val elapsedMs: Long)

/** Debug research candidate. No confidence or semantic understanding is inferred from MOS. */
class LocalResearchScorer(private val context: Context) : AutoCloseable {
    private val environment by lazy { OrtEnvironment.getEnvironment().also { it.setTelemetry(false) } }
    private var session: OrtSession? = null
    val available get() = context.assets.list("")?.contains("gaic-mobilenet.onnx") == true

    fun evaluate(image: Bitmap, maxRelativeZoom: Float, isActive: () -> Boolean): LocalResearchResult {
        val started = SystemClock.elapsedRealtime()
        if (!isActive()) throw SceneServiceException("cancelled", "Research scan cancelled.")
        val model = session ?: context.assets.openFd("gaic-mobilenet.onnx").use { asset ->
            java.io.FileInputStream(asset.fileDescriptor).use { stream ->
                val mapped = stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                    environment.createSession(mapped, options).also { session = it }
                }
            }
        }
        val factor = 256.0 / minOf(image.width, image.height)
        val width = round(image.width * factor / 32).toInt() * 32
        val height = round(image.height * factor / 32).toInt() * 32
        val rgba = Mat(); val rgb = Mat(); val resized = Mat(); val normalized = Mat()
        try {
            Utils.bitmapToMat(image, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.resize(rgb, resized, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            resized.convertTo(normalized, CvType.CV_32FC3, 1.0 / 256)
            val interleaved = FloatArray(width * height * 3); normalized.get(0, 0, interleaved)
            val pixels = FloatArray(interleaved.size)
            val mean = floatArrayOf(.485f, .456f, .406f); val deviation = floatArrayOf(.229f, .224f, .225f)
            for (channel in 0..2) for (pixel in 0 until width * height) {
                pixels[channel * width * height + pixel] = (interleaved[pixel * 3 + channel] - mean[channel]) / deviation[channel]
            }
            val boxes = ShotGeometry.options.flatMap { option ->
                val b = option.box
                listOf(floor(b.left * width), floor(b.top * height), ceil(b.right * width), ceil(b.bottom * height))
            }.toFloatArray()
            if (!isActive()) throw SceneServiceException("cancelled", "Research scan cancelled.")
            val output = OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), longArrayOf(1, 3, height.toLong(), width.toLong())).use { input ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(boxes), longArrayOf(9, 4)).use { rois ->
                    model.run(mapOf("image" to input, "boxes" to rois)).use { results ->
                        (results[0].value as FloatArray).clone()
                    }
                }
            }
            check(output.size == ShotGeometry.options.size && output.all { it.isFinite() })
            if (!isActive()) throw SceneServiceException("cancelled", "Research scan cancelled.")
            val scores = output.mapIndexed { index, value ->
                if (ShotGeometry.options[index].zoom <= maxRelativeZoom + .001f) value * .8f + 2.95f else Float.NEGATIVE_INFINITY
            }
            val index = scores.indices.maxBy { scores[it] }
            return LocalResearchResult(ShotGeometry.options[index], scores, SystemClock.elapsedRealtime() - started)
        } finally { listOf(rgba, rgb, resized, normalized).forEach { it.release() } }
    }
    override fun close() { session?.close(); session = null }
}
