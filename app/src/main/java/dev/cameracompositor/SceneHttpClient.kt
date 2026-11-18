package dev.cameracompositor

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Bounded transport with cancellation and a testable endpoint. No logging interceptors. */
class SceneHttpClient(
    private val endpoint: HttpUrl = "https://generativelanguage.googleapis.com/".toHttpUrl(),
    deadlineMs: Long = 35_000,
) : AutoCloseable {
    private val active = AtomicReference<Call?>(null)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(deadlineMs, TimeUnit.MILLISECONDS)
        .eventListenerFactory { call ->
            val trace = call.request().tag(ScanDiagnostics::class.java)!!
            object : EventListener() {
                override fun dnsStart(call: Call, domainName: String) { trace.mark(ScanDiagnostics.Stage.DNS) }
                override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) { trace.mark(ScanDiagnostics.Stage.CONNECT) }
                override fun secureConnectStart(call: Call) { trace.mark(ScanDiagnostics.Stage.TLS) }
                override fun requestBodyStart(call: Call) { trace.mark(ScanDiagnostics.Stage.UPLOAD) }
                override fun requestBodyEnd(call: Call, byteCount: Long) { trace.mark(ScanDiagnostics.Stage.WAIT_RESPONSE) }
                override fun responseHeadersEnd(call: Call, response: Response) {
                    trace.httpCode = response.code; trace.mark(ScanDiagnostics.Stage.DOWNLOAD)
                }
            }
        }.build()

    fun execute(json: String, credential: String, model: String, trace: ScanDiagnostics,
                isActive: () -> Boolean = { true }): String {
        require(model.matches(Regex("gemini-[a-zA-Z0-9.-]+")))
        val url = endpoint.newBuilder().addPathSegments("v1beta/models/$model:generateContent").build()
        val request = Request.Builder().url(url).header("x-goog-api-key", credential)
            .tag(ScanDiagnostics::class.java, trace)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newCall(request)
        check(active.compareAndSet(null, call)) { "A scene request is already running." }
        try {
            if (!isActive()) { call.cancel(); throw SceneServiceException("cancelled", "Scan cancelled.") }
            trace.mark(ScanDiagnostics.Stage.CONNECT)
            return call.execute().use { response ->
                trace.httpCode = response.code
                if (!isActive()) throw SceneServiceException("cancelled", "Scan cancelled.")
                if (!response.isSuccessful) throw SceneServiceException("http_${response.code}", when (response.code) {
                    400 -> "The service rejected this request. Check the model and request compatibility."
                    401, 403 -> "The service rejected access. Check the key and project permissions."
                    404 -> "The selected model is unavailable."
                    429 -> "The service quota is exhausted. Local camera tools remain available."
                    else -> "The scene service is unavailable (HTTP ${response.code})."
                })
                val source = response.body?.source() ?: throw SceneServiceException("empty", "The service returned an empty response.")
                val bytes = okio.Buffer()
                while (true) {
                    if (!isActive()) throw SceneServiceException("cancelled", "Scan cancelled.")
                    if (source.read(bytes, 8192) == -1L) break
                    if (bytes.size > 256_000) throw SceneServiceException("too_large", "The scene response exceeded its size limit.")
                }
                bytes.readUtf8()
            }
        } catch (error: IOException) {
            val failure = when {
                error is SceneServiceException -> error
                !isActive() -> SceneServiceException("cancelled", "Scan cancelled.")
                error is SocketTimeoutException || (error is java.io.InterruptedIOException && call.isCanceled()) ->
                    SceneServiceException("timeout", "Scene request timed out during ${trace.stage.name.lowercase().replace('_', ' ')}. Open Options > Scan diagnostics for timings.")
                error is UnknownHostException -> SceneServiceException("dns", "The scene service address could not be reached.")
                else -> SceneServiceException("network", "The network request failed during ${trace.stage.name.lowercase().replace('_', ' ')}.")
            }
            trace.outcome = failure.category
            throw failure
        } finally { active.compareAndSet(call, null) }
    }
    fun cancel() { active.getAndSet(null)?.cancel() }
    override fun close() { cancel() }
}
