package dev.cameracompositor

/** Recover a visible outline from another snapshot in the current scan. */
object RegisteredLandmark {
    fun recover(previous: SceneLayout, current: SceneLayout, map: EnvironmentMap,
                from: EnvironmentView, to: EnvironmentView): SubjectBox? {
        val box = previous.framedDetail ?: return null
        if (to.timeMs - from.timeMs !in 1..700) return null
        val corners = map.projectPoints(from.id, to.id, ShotGeometry.corners(box)) ?: return null
        if (!ShotGeometry.valid(corners) || corners.any { it.x !in 0f..1f || it.y !in 0f..1f }) return null
        // Check the landmark itself. Background features may still register after it moves or disappears.
        val samples = List(96) { i ->
            ScenePoint(box.left + (box.right - box.left) * ((i % 12 + .5f) / 12),
                box.top + (box.bottom - box.top) * ((i / 12 + .5f) / 8))
        }
        val mapped = map.projectPoints(from.id, to.id, samples) ?: return null
        val before = IntArray(samples.size) { (255 * previous.lightAt(samples[it])).toInt() }
        val after = IntArray(samples.size) { (255 * current.lightAt(mapped[it])).toInt() }
        if (before.max() - before.min() < 20 || after.max() - after.min() < 20 ||
            !FrameSignature.sameSelectedView(before, after)) return null
        return SubjectBox(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }
}
