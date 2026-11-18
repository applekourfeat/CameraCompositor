package dev.cameracompositor

import org.json.JSONArray
import org.json.JSONObject

/** Reports contain fixed events and durations only. Never keys, photos or exception text. */
class ScanDiagnostics(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    enum class Stage { PREPARE, DNS, CONNECT, TLS, UPLOAD, WAIT_RESPONSE, DOWNLOAD, PARSE, COMPLETE }
    private val started = clock()
    private val events = mutableListOf<Pair<Stage, Long>>()
    @Volatile var stage = Stage.PREPARE
        private set
    @Volatile var httpCode: Int? = null
    @Volatile var outcome = "running"

    @Synchronized fun mark(next: Stage) {
        stage = next
        if (events.size < 32) events.add(next to (clock() - started).coerceAtLeast(0))
    }
    @Synchronized fun report(): String = JSONObject()
        .put("schema", 1).put("outcome", outcome).put("stage", stage.name)
        .put("elapsedMs", (clock() - started).coerceAtLeast(0)).put("httpCode", httpCode ?: JSONObject.NULL)
        .put("events", JSONArray(events.map { (name, time) -> JSONObject().put("stage", name.name).put("atMs", time) }))
        .toString(2)
}

class SceneServiceException(val category: String, message: String) : java.io.IOException(message)
