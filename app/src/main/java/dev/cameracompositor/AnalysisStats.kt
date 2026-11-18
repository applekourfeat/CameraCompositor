package dev.cameracompositor

/** Bounded timing samples. This measures analyzer work, not rendered preview FPS. */
class AnalysisStats {
    private val times = ArrayDeque<Long>()
    private var frames = 0L
    private var errors = 0L
    @Synchronized fun record(durationMs: Long, failed: Boolean) {
        frames++; if (failed) errors++
        if (times.size == 600) times.removeFirst()
        times.addLast(durationMs)
    }
    @Synchronized fun report(): String {
        val ordered = times.sorted()
        val p95 = if (ordered.isEmpty()) 0 else ordered[((ordered.size - 1) * .95).toInt()]
        return "Analyzer: $frames frames, $errors errors; recent p95 work $p95 ms. Preview FPS is unmeasured."
    }
}
