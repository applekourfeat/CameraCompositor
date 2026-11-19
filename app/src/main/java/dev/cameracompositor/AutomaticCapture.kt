package dev.cameracompositor

import kotlin.math.hypot

sealed interface ShutterUpdate {
    data object Waiting : ShutterUpdate
    data class Counting(val seconds: Int) : ShutterUpdate
    data object Capture : ShutterUpdate
}

/** A fresh viewfinder observation must sustain the countdown and authorize the shutter. */
class AutomaticShutter {
    private data class Sample(val timeMs: Long, val corners: List<ScenePoint>)
    private var anchor: Sample? = null
    private var previous: Sample? = null
    private var fired = false

    fun update(timeMs: Long, nowMs: Long, corners: List<ScenePoint>, ready: Boolean, verified: Boolean = true): ShutterUpdate {
        if (fired) return ShutterUpdate.Waiting
        if (!ready || !verified || nowMs - timeMs !in 0L..300L || !ShotGeometry.valid(corners)) {
            interrupt()
            return ShutterUpdate.Waiting
        }
        val sample = Sample(timeMs, corners)
        val last = previous
        val first = anchor
        if (last == null || first == null || timeMs - last.timeMs !in 1L..320L ||
            drift(last.corners, corners) > .021f || drift(first.corners, corners) > .035f) {
            anchor = sample
        }
        previous = sample
        val held = timeMs - (anchor?.timeMs ?: timeMs)
        if (held < 500) return ShutterUpdate.Waiting
        val remaining = 3500 - held
        if (remaining > 0) return ShutterUpdate.Counting(((remaining + 999) / 1000).toInt())
        fired = true
        return ShutterUpdate.Capture
    }

    private fun drift(a: List<ScenePoint>, b: List<ScenePoint>) = a.indices.maxOf { index ->
        hypot(a[index].x - b[index].x, a[index].y - b[index].y)
    }

    fun interrupt() { anchor = null; previous = null }
    fun reset() { interrupt(); fired = false }
}

data class LiveScene(val timeMs: Long, val signature: IntArray)

/** Wait for a sustained change before spending more CPU or capturing the same subject again. */
class AutomaticScanGate {
    private var references = emptyList<IntArray>()
    private var readyAt = 0L
    private var changedAt: Long? = null
    private var afterCapture = false
    private var retried = false

    fun reset() { references = emptyList(); readyAt = 0; changedAt = null; afterCapture = false; retried = false }

    fun retryOnce(nowMs: Long): Boolean {
        if (retried || afterCapture) return false
        retried = true
        references = emptyList()
        readyAt = nowMs + 600
        changedAt = null
        return true
    }

    fun waitForChange(nowMs: Long, captured: Boolean, vararg samples: LiveScene?) {
        references = samples.filterNotNull().map { it.signature }
        readyAt = nowMs + 650
        changedAt = null
        afterCapture = captured
        retried = false
    }

    fun ready(sample: LiveScene?, nowMs: Long): Boolean {
        if (sample == null || nowMs < readyAt || nowMs - sample.timeMs !in 0L..400L) {
            changedAt = null
            return false
        }
        if (references.isEmpty()) return true
        val similar = references.any { signature ->
            if (afterCapture) FrameSignature.sameScene(signature, sample.signature)
            else FrameSignature.sameAngle(signature, sample.signature)
        }
        if (similar) {
            changedAt = null
            return false
        }
        val since = changedAt ?: sample.timeMs.also { changedAt = it }
        if (sample.timeMs - since < 450) return false
        reset()
        return true
    }
}
