package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test

class RegisteredLandmarkTest {
    private fun scene(shift: Int = 0, invert: Boolean = false): SceneLayout {
        val w = SceneLayout.WIDTH
        val h = SceneLayout.HEIGHT
        return SceneLayout(IntArray(w * h) { i ->
            val x = i % w - shift
            val y = i / w
            val light = if (x in 38..82 && y in 61..110) 55 + (y - 61) else 196
            (if (invert) 255 - light else light) * 0x010101
        }, null)
    }

    private fun recover(second: SceneLayout, dx: Float, inliers: Int = 36, age: Long = 160): SubjectBox? {
        val from = EnvironmentView(0, 1000, 1f, 2f, null)
        fun warp(x: Float) = ImageProjection(listOf(1f, 0f, x, 0f, 1f, 0f, 0f, 0f, 1f))
        val link = ViewRegistration(inliers, 40, kotlin.math.abs(dx), .2f, .45f, warp(dx), warp(-dx))
        val to = EnvironmentView(1, 1000 + age, 1f, 2f, link, 0)
        val map = EnvironmentMap(1).apply { add(from); add(to) }
        return RegisteredLandmark.recover(scene(), second, map, from, to)
    }

    @Test fun projectsOutlineIntoNewFrame() {
        val dx = 6f / SceneLayout.WIDTH
        val before = scene().framedDetail!!
        val after = recover(scene(6), dx)!!
        assertEquals(before.left + dx, after.left, .0001f)
        assertEquals(before.bottom, after.bottom, .0001f)
    }

    @Test fun missingOverlapExpiredAndOffscreenRegionsAreDiscarded() {
        assertNull(recover(scene(), 0f, inliers = 12))
        assertNull(recover(scene(), 0f, age = 800))
        assertNull(recover(scene(), .8f))
    }

    @Test fun matchingBackgroundCannotKeepAChangedLandmark() {
        assertNull(recover(scene(invert = true), 0f))
        assertNull(recover(SceneLayout(IntArray(SceneLayout.WIDTH * SceneLayout.HEIGHT) { 0x989898 }, null), 0f))
    }
}
