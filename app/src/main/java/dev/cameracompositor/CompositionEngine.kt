package dev.cameracompositor

import kotlin.math.abs

/** Normalized coordinates in the visible viewfinder, after rotation/cropping/mirroring. */
data class SubjectBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    val valid get() = listOf(left, top, right, bottom).all { it.isFinite() } && right > left && bottom > top
}

enum class Cue { SEARCHING, SPACE, LEFT, RIGHT, UP, DOWN, ALIGNED }

/** This is a framing rule, separate from ML subject detection. It does not score aesthetics. */
object CompositionEngine {
    fun evaluate(box: SubjectBox?, targetX: Float): Cue {
        if (box == null || !box.valid) return Cue.SEARCHING
        if (box.left < 0.025f || box.top < 0.025f || box.right > 0.975f || box.bottom > 0.975f) {
            return Cue.SPACE
        }
        val dx = box.centerX - targetX
        val dy = box.centerY - 0.5f
        if (abs(dx) <= 0.055f && abs(dy) <= 0.055f) return Cue.ALIGNED
        // Panning right moves the subject left in the viewfinder, toward the target.
        return if (abs(dx) >= abs(dy)) {
            if (dx > 0f) Cue.RIGHT else Cue.LEFT
        } else {
            if (dy > 0f) Cue.DOWN else Cue.UP
        }
    }
}

/** Require a sustained alignment before showing the success state. */
class AlignmentHold(private val durationMs: Long = 650) {
    private var alignedSince: Long? = null
    fun update(cue: Cue, nowMs: Long): Boolean {
        if (cue != Cue.ALIGNED) { reset(); return false }
        val since = alignedSince ?: nowMs.also { alignedSince = it }
        return nowMs - since >= durationMs
    }
    fun reset() { alignedSince = null }
}
