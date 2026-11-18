package dev.cameracompositor

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

class CloudShotPlanner(private val settings: PlannerSettings) : AutoCloseable {
    companion object {
        /** Metadata-only check: validates key/model access without sending a camera image. */
        fun checkAccess(credential: String, model: String): String {
            require(credential.length >= 20 && credential.none { it.isWhitespace() }) { "Paste the complete key first." }
            require(model.matches(Regex("gemini-[a-zA-Z0-9.-]+"))) { "Enter a valid Gemini model ID." }
            val client = URL("https://generativelanguage.googleapis.com/v1beta/models/$model").openConnection() as HttpURLConnection
            try {
                client.connectTimeout = 10_000; client.readTimeout = 10_000; client.instanceFollowRedirects = false
                client.setRequestProperty("x-goog-api-key", credential)
                val code = client.responseCode
                check(code in 200..299) { "Key/model access check failed (HTTP $code). Check the complete key, project permissions, and model ID." }
                val body = client.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096); val text = StringBuilder()
                    while (true) { val n = reader.read(buffer); if (n < 0) break; text.append(buffer, 0, n); check(text.length < 100_000) }
                    text.toString()
                }
                val methods = org.json.JSONObject(body).optJSONArray("supportedGenerationMethods")
                check(methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }) {
                    "This model does not expose the required scene-planning endpoint."
                }
                return "Key and model access confirmed. Save to use scene scans."
            } finally { client.disconnect() }
        }
    }
    @Volatile private var connection: HttpURLConnection? = null
    private val requestSequence = AtomicLong()
    val backendLabel get() = "Google Gemini · ${settings.model}"

    fun beginRequest(): Long = requestSequence.incrementAndGet()
    fun choose(frame: Bitmap, requestId: Long, maxRelativeZoom: Float, onStage: (String) -> Unit): ModelChoice {
        fun ensureActive() { check(requestSequence.get() == requestId) { "Scan cancelled" } }
        ensureActive()
        check(settings.consent) { "Allow a single scene image upload before scanning." }
        val credential = settings.key() ?: error("Add your personal API key in Options > AI setup.")
        settings.reserveScan()
        onStage("Finding a composition…")
        val bytes = ByteArrayOutputStream().use { output -> frame.compress(Bitmap.CompressFormat.JPEG, 84, output); output.toByteArray() }
        val request = GeminiProtocol.request(Base64.encodeToString(bytes, Base64.NO_WRAP), maxRelativeZoom).toByteArray(Charsets.UTF_8)
        val client = URL("https://generativelanguage.googleapis.com/v1beta/models/${settings.model}:generateContent")
            .openConnection() as HttpURLConnection
        connection = client
        try {
            ensureActive()
            client.requestMethod = "POST"; client.connectTimeout = 10_000; client.readTimeout = 30_000
            client.instanceFollowRedirects = false
            client.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            client.setRequestProperty("x-goog-api-key", credential)
            client.doOutput = true; client.setFixedLengthStreamingMode(request.size)
            client.outputStream.use { ensureActive(); it.write(request) }
            val code = client.responseCode
            ensureActive()
            if (code !in 200..299) {
                // Keep credentials and provider error bodies out of logs and UI.
                val description = when (code) {
                    400 -> "The scene request or selected model was rejected. Check the model ID in AI setup."
                    401, 403 -> "The API key or project cannot use this model. Check your key and model access in AI Studio."
                    404 -> "That model is unavailable. Choose a supported model in AI setup."
                    429 -> "Google's rate limit or quota was reached. Wait or check AI Studio usage."
                    else -> "Scene service unavailable (HTTP $code). Try again later."
                }
                throw IllegalStateException(description)
            }
            val body = client.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096); val content = StringBuilder()
                while (true) {
                    val count = reader.read(buffer); if (count < 0) break
                    content.append(buffer, 0, count); check(content.length < 256_000) { "Scene response was unexpectedly large." }
                }
                content.toString()
            }
            ensureActive()
            return GeminiProtocol.response(body, maxRelativeZoom)
        } finally { client.disconnect(); connection = null }
    }
    fun cancel() { requestSequence.incrementAndGet(); connection?.disconnect() }
    override fun close() { cancel() }
}
