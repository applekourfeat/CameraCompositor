package dev.cameracompositor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.FileInputStream
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/** Image-only TinyCLIP inference. Session and tensors stay on the model executor. */
class SceneRecognition(private val context: Context) : AutoCloseable {
    private val environment by lazy { OrtEnvironment.getEnvironment().also { it.setTelemetry(false) } }
    private var session: OrtSession? = null
    private var unavailable = false

    fun scores(image: Bitmap): FloatArray? {
        if (unavailable) return null
        return try {
            val model = session ?: openModel().also { session = it }
            val count = SIZE * SIZE
            val input = FloatArray(count * 3)
            val rgb = IntArray(count)
            val resized = Bitmap.createScaledBitmap(image, SIZE, SIZE, true)
            try {
                resized.getPixels(rgb, 0, SIZE, 0, 0, SIZE, SIZE)
            } finally {
                if (resized !== image) resized.recycle()
            }
            for (i in rgb.indices) {
                input[i] = ((rgb[i] shr 16 and 255) / 255f - .48145466f) / .26862954f
                input[count + i] = ((rgb[i] shr 8 and 255) / 255f - .4578275f) / .26130258f
                input[2 * count + i] = ((rgb[i] and 255) / 255f - .40821073f) / .27577711f
            }
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
                model.run(mapOf("image" to tensor)).use { output ->
                    val scores = (output[0] as OnnxTensor).floatBuffer
                    val size = SceneRegions.GRID * SceneRegions.GRID * SceneRegions.PROMPT_GROUP.size
                    if (scores.remaining() != size) null
                    else FloatArray(size).also { scores.get(it) }.takeIf { it.all(Float::isFinite) }
                }
            }
        } catch (_: Exception) {
            unavailable = true
            close()
            null
        } catch (_: LinkageError) {
            unavailable = true
            null
        }
    }

    private fun openModel(): OrtSession = context.assets.openFd("scene-regions-192.onnx").use { asset ->
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

    private companion object { const val SIZE = 192 }
}
