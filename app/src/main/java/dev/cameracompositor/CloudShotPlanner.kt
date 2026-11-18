package dev.cameracompositor

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

class CloudShotPlanner(private val settings: PlannerSettings, private val saveDiagnostic: (String) -> Unit = {}) : AutoCloseable {
    companion object {
        /** Metadata establishes access only, not successful image inference. */
        fun checkAccess(credential: String, model: String): String {
            require(credential.length >= 20 && credential.none { it.isWhitespace() }) { "Paste the complete key first." }
            require(model.matches(Regex("gemini-[a-zA-Z0-9.-]+"))) { "Enter a valid Gemini model ID." }
            val client = URL("https://generativelanguage.googleapis.com/v1beta/models/$model").openConnection() as HttpURLConnection
            try {
                client.connectTimeout = 10_000; client.readTimeout = 10_000; client.instanceFollowRedirects = false
                client.setRequestProperty("x-goog-api-key", credential)
                val code = client.responseCode
                check(code in 200..299) { "Key/model access check failed (HTTP $code)." }
                val body = client.inputStream.bufferedReader().use { reader ->
                    val text = StringBuilder(); val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer); if (count < 0) break
                        text.append(buffer, 0, count); check(text.length < 100_000) { "Response exceeded its size limit." }
                    }
                    text.toString()
                }
                val methods = org.json.JSONObject(body).optJSONArray("supportedGenerationMethods")
                check(methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }) {
                    "This model does not expose the required endpoint."
                }
                return "Model access confirmed. Use the test-image diagnostic to check inference."
            } catch (_: java.net.SocketTimeoutException) {
                error("The access check timed out. A scene request has not been tested.")
            } catch (_: java.io.IOException) {
                error("The access check could not reach the service.")
            } finally { client.disconnect() }
        }
    }
    private val transport = SceneHttpClient()
    private val sequence = AtomicLong()
    val backendLabel get() = "Personal research service · ${settings.model}"
    fun beginRequest(): Long { transport.cancel(); return sequence.incrementAndGet() }

    fun choose(frame: Bitmap, requestId: Long, maxRelativeZoom: Float, onStage: (String) -> Unit): ModelChoice =
        chooseCore(frame, requestId, maxRelativeZoom, true, onStage)

    fun testService(chart: Bitmap, requestId: Long): ModelChoice = chooseCore(chart, requestId, 1.8f, false) {}

    private fun chooseCore(frame: Bitmap, requestId: Long, maxRelativeZoom: Float,
                           requireSceneConsent: Boolean, onStage: (String) -> Unit): ModelChoice {
        val trace = ScanDiagnostics()
        fun ensureActive() { if (sequence.get() != requestId) throw SceneServiceException("cancelled", "Scan cancelled.") }
        try {
            ensureActive()
            check(!requireSceneConsent || settings.consent) { "Allow the image upload before scanning." }
            val credential = settings.key() ?: error("Add the personal research key in AI setup.")
            settings.reserveScan()
            onStage("Requesting a research composition…")
            val bytes = ByteArrayOutputStream().use { output ->
                frame.compress(Bitmap.CompressFormat.JPEG, 84, output); output.toByteArray()
            }
            val request = GeminiProtocol.request(Base64.encodeToString(bytes, Base64.NO_WRAP), maxRelativeZoom)
            val body = transport.execute(request, credential, settings.model, trace) { sequence.get() == requestId }
            ensureActive(); trace.mark(ScanDiagnostics.Stage.PARSE)
            val result = try { GeminiProtocol.response(body, maxRelativeZoom) }
            catch (_: RuntimeException) {
                throw SceneServiceException("format", "The service returned an invalid composition. Camera controls were kept unchanged.")
            }
            trace.outcome = "success"; trace.mark(ScanDiagnostics.Stage.COMPLETE)
            return result
        } catch (error: Exception) {
            if (trace.outcome == "running") trace.outcome = (error as? SceneServiceException)?.category ?: "setup"
            throw error
        } finally { saveDiagnostic(trace.report()) }
    }
    fun cancel() { sequence.incrementAndGet(); transport.cancel() }
    override fun close() { cancel() }
}
