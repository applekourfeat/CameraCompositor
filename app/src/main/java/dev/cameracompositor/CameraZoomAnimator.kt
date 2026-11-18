package dev.cameracompositor

import android.os.Handler
import android.os.SystemClock
import androidx.camera.view.CameraController
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Smooth on a logarithmic scale so the preview grows at a consistent apparent rate. */
class ZoomRamp(from: Float, val target: Float) {
    init { require(from.isFinite() && target.isFinite() && from > 0 && target > 0) }
    private val start = ln(from)
    private val end = ln(target)
    val durationMs = (230 + abs(end - start) * 680).toLong().coerceIn(260, 950)

    fun next(previous: Float, elapsedMs: Long): Float {
        val fraction = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val eased = fraction * fraction * (3 - 2 * fraction)
        val current = ln(previous)
        val desired = start + (end - start) * eased
        return exp(current + (desired - current).coerceIn(-.065f, .065f))
    }
}

/** Keeps one zoom request in flight. The time base is independent of the analysis rate. */
class CameraZoomAnimator(private val handler: Handler, private val main: Executor) {
    private class Animation(val camera: CameraController, val ramp: ZoomRamp, var applied: Float,
                            val started: Long, val onFailure: () -> Unit)
    private var animation: Animation? = null
    var settledAt = 0L
        private set
    val moving get() = animation != null

    fun moveTo(camera: CameraController, desired: Float, onFailure: () -> Unit) {
        val zoom = camera.zoomState.value ?: return
        val target = desired.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        if (animation?.let { it.camera === camera && abs(it.ramp.target - target) < .01f } == true) return
        if (abs(ln(target / zoom.zoomRatio)) < .009f) return
        stop()
        val next = Animation(camera, ZoomRamp(zoom.zoomRatio, target), zoom.zoomRatio,
            SystemClock.elapsedRealtime(), onFailure)
        animation = next
        handler.postDelayed({ step(next) }, 32)
    }

    private fun step(active: Animation) {
        if (animation !== active) return
        val elapsed = SystemClock.elapsedRealtime() - active.started
        val next = active.ramp.next(active.applied, elapsed)
        if (abs(ln(active.ramp.target / active.applied)) < .004f && elapsed >= active.ramp.durationMs) {
            stop()
            return
        }
        if (abs(ln(next / active.applied)) < .004f) {
            handler.postDelayed({ step(active) }, 32)
            return
        }
        val sentAt = SystemClock.elapsedRealtime()
        val result = runCatching { active.camera.setZoomRatio(next) }.getOrElse { fail(active); return }
        val timeout = Runnable { fail(active) }
        handler.postDelayed(timeout, 800)
        result.addListener({
            handler.removeCallbacks(timeout)
            if (animation === active) {
                runCatching { result.get() }.onSuccess {
                    active.applied = next
                    handler.postDelayed({ step(active) }, (32 - (SystemClock.elapsedRealtime() - sentAt)).coerceAtLeast(0))
                }.onFailure { fail(active) }
            }
        }, main)
    }

    private fun fail(active: Animation) {
        if (animation !== active) return
        stop()
        active.onFailure()
    }

    fun stop() {
        if (animation != null) settledAt = SystemClock.elapsedRealtime()
        animation = null
    }
}
