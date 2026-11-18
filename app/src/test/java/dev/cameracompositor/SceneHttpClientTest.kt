package dev.cameracompositor

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SceneHttpClientTest {
    @Test fun `multi view scene graph survives transport and parsing without entering diagnostics`() {
        MockWebServer().use { server ->
            val views=listOf(EnvironmentView(0,1000,1f,3.2f,null),EnvironmentView(1,1600,1f,3.2f,null))
            val plan="""{"decision":"guide","viewId":0,"centerX":500,"centerY":500,"zoom":2,"subjectId":"bridge","reason":"Isolate the bridge from foreground clutter.","sceneSummary":"A bridge lies beyond foreground clutter.","subjects":[{"id":"bridge","name":"Bridge","viewIds":[0,1]}],"relationships":[]}"""
            val response=org.json.JSONObject().put("candidates",org.json.JSONArray().put(org.json.JSONObject()
                .put("finishReason","STOP").put("content",org.json.JSONObject().put("parts",org.json.JSONArray().put(org.json.JSONObject().put("text",plan))))))
            server.enqueue(MockResponse().setBody(response.toString()))
            SceneHttpClient(server.url("/")).use { transport ->
                val trace=ScanDiagnostics()
                val body=transport.execute(EnvironmentProtocol.request(views.map { EncodedEnvironmentView(it,"private-view-${it.id}") },1),"private-key","gemini-test",trace)
                val assessment=EnvironmentProtocol.assessment(body,views,1)
                assertEquals(0,(assessment.decision as EnvironmentDecision.Guide).viewId)
                assertEquals("bridge",assessment.subjects.single().id)
                assertFalse(trace.report().contains("private")); assertFalse(trace.report().contains("Bridge"))
                assertEquals(1,server.requestCount)
            }
        }
    }
    @Test fun `known request records stages without recording credentials or image content`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            SceneHttpClient(server.url("/")).use { transport ->
                val trace = ScanDiagnostics()
                assertEquals("{\"ok\":true}", transport.execute("private-test-image", "private-test-key", "gemini-test", trace))
                val received = server.takeRequest()
                assertEquals("/v1beta/models/gemini-test:generateContent", received.path)
                assertEquals("private-test-key", received.getHeader("x-goog-api-key"))
                val report = trace.report()
                assertFalse(report.contains("private-test"))
                assertTrue(report.contains("UPLOAD")); assertTrue(report.contains("WAIT_RESPONSE"))
                assertEquals(200, trace.httpCode)
            }
        }
    }
    @Test fun `quota response neither retries nor exposes provider error body`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("secret-key / private image details"))
            SceneHttpClient(server.url("/")).use { transport ->
                val trace = ScanDiagnostics()
                val error = assertThrows(SceneServiceException::class.java) { transport.execute("photo", "key", "gemini-test", trace) }
                assertEquals("http_429", error.category)
                assertEquals(1, server.requestCount)
                assertFalse(error.message!!.contains("secret")); assertFalse(trace.report().contains("private"))
            }
        }
    }
    @Test fun `slow service times out at waiting stage within an overall deadline`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{}"))
            SceneHttpClient(server.url("/"), 300).use { transport ->
                val trace = ScanDiagnostics()
                val error = assertThrows(SceneServiceException::class.java) { transport.execute("photo", "key", "gemini-test", trace) }
                assertEquals("timeout", error.category)
                assertEquals(ScanDiagnostics.Stage.WAIT_RESPONSE, trace.stage)
                assertTrue(error.message!!.contains("wait response"))
            }
        }
    }
    @Test fun `response body cannot exceed memory limit`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(270_000)))
            SceneHttpClient(server.url("/")).use { transport ->
                val error = assertThrows(SceneServiceException::class.java) { transport.execute("photo", "key", "gemini-test", ScanDiagnostics()) }
                assertEquals("too_large", error.category)
            }
        }
    }
    @Test fun `cancellation releases transport for another request`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{}"))
            server.enqueue(MockResponse().setBody("new request"))
            SceneHttpClient(server.url("/")).use { transport ->
                val active = AtomicBoolean(true)
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val task = executor.submit<String> {
                        try { transport.execute("photo", "key", "gemini-test", ScanDiagnostics()) { active.get() }; "unexpected" }
                        catch (error: SceneServiceException) { error.category }
                    }
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    active.set(false); transport.cancel()
                    assertEquals("cancelled", task.get(2, TimeUnit.SECONDS))
                    assertEquals("new request", transport.execute("photo", "key", "gemini-test", ScanDiagnostics()))
                } finally { executor.shutdownNow() }
            }
        }
    }
    @Test fun `redirects cannot forward the credential to another endpoint`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"))
            SceneHttpClient(server.url("/")).use { transport ->
                val error = assertThrows(SceneServiceException::class.java) { transport.execute("photo", "key", "gemini-test", ScanDiagnostics()) }
                assertEquals("http_302", error.category); assertEquals(1, server.requestCount)
            }
        }
    }
}
