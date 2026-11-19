package dev.cameracompositor

import kotlin.math.abs

enum class ShotHandoff { SHOW, VERIFY, RESCAN }

/** Handoff for a single captured frame. Feature matching still controls the shutter. */
object LiveShotHandoff {
    const val VERIFY_WINDOW_MS = 1_800L

    fun assess(source: LiveScene, live: LiveScene?, sourceZoom: Float, liveZoom: Float, nowMs: Long): ShotHandoff {
        if (live == null || live.timeMs < source.timeMs || nowMs - live.timeMs !in 0L..480L ||
            nowMs - source.timeMs !in 0L..6_000L ||
            !sourceZoom.isFinite() || !liveZoom.isFinite() || sourceZoom <= 0f ||
            abs(liveZoom / sourceZoom - 1f) > .027f) return ShotHandoff.RESCAN
        return if (FrameSignature.sameSelectedView(source.signature, live.signature)) ShotHandoff.SHOW else ShotHandoff.VERIFY
    }
}
