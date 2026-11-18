package dev.cameracompositor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiProtocolTest {
    private fun wrap(plan: String, finish: String = "STOP"): String = JSONObject().put("candidates", JSONArray().put(
        JSONObject().put("finishReason", finish).put("content", JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", plan)))))).toString()

    @Test fun `single image request asks for bounded structured framing`() {
        val request = JSONObject(GeminiProtocol.request("test-image-base64"))
        val contents = request.getJSONArray("contents")
        assertEquals(1, contents.length())
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals("image/jpeg", parts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
        val config = request.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertFalse(config.has("temperature"))
        assertFalse(request.has("apiKey"))
        assertEquals(1.8, config.getJSONObject("responseJsonSchema").getJSONObject("properties").getJSONObject("zoom").getDouble("maximum"), .0001)
    }
    @Test fun `request respects remaining camera zoom range`() {
        val config = JSONObject(GeminiProtocol.request("image", 1.2f)).getJSONObject("generationConfig")
        assertEquals(1.2, config.getJSONObject("responseJsonSchema").getJSONObject("properties").getJSONObject("zoom").getDouble("maximum"), .0001)
    }
    @Test fun `response cannot exceed remaining camera zoom range`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeminiProtocol.response(wrap("""{"centerX":500,"centerY":500,"zoom":1.6,"reason":"Frame the palm crown."}"""), 1.2f)
        }
    }
    @Test fun `provider framing yields a valid photo crop with its stated reason`() {
        val choice = GeminiProtocol.response(wrap("""{"centerX":700,"centerY":320,"zoom":1.6,"reason":"Palm crown balances the reflective building."}"""))
        assertEquals(-1, choice.cropId)
        assertTrue(choice.explanation.startsWith("Palm crown"))
        val box = choice.customCrop!!
        assertTrue(ShotGeometry.valid(ShotGeometry.corners(box)))
        assertEquals(.625f, box.right - box.left, .0001f)
        assertTrue(box.left >= 0 && box.top >= 0 && box.right <= 1 && box.bottom <= 1)
    }
    @Test fun `truncated responses are rejected rather than moving the camera`() {
        assertThrows(IllegalStateException::class.java) { GeminiProtocol.response(wrap("{}", "MAX_TOKENS")) }
    }
    @Test fun `missing and invalid geometry cannot become camera commands`() {
        for (plan in listOf("{}", """{"centerX":500,"centerY":500,"zoom":8,"reason":"Some framing"}""",
            """{"centerX":-20,"centerY":500,"zoom":1.2,"reason":"Some framing"}""")) {
            assertThrows(RuntimeException::class.java) { GeminiProtocol.response(wrap(plan)) }
        }
        assertThrows(IllegalArgumentException::class.java) { GeminiProtocol.response("{}") }
    }
    @Test fun `empty explanation is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeminiProtocol.response(wrap("""{"centerX":500,"centerY":500,"zoom":1,"reason":""}"""))
        }
    }
    @Test fun `thoughts do not contaminate structured output`() {
        val body = JSONObject(wrap("""{"centerX":500,"centerY":500,"zoom":1,"reason":"Keep the complete scene for context."}"""))
        val parts = body.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        parts.put(JSONObject().put("thought", true).put("text", "not part of the answer"))
        assertEquals(0f, GeminiProtocol.response(body.toString()).customCrop!!.left, .0001f)
    }
}
