package dev.cameracompositor

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class CompositionStylesTest {
    private val subject = DetectedSubject(SubjectBox(.32f, .22f, .55f, .51f), 15, .74f)
    private val current = EnvironmentView(0, 1, 1f, 2.2f, null)

    private fun scene(busy: Boolean = false): SceneLayout {
        val pixels = IntArray(SceneLayout.WIDTH * SceneLayout.HEIGHT) { i ->
            val x = i % SceneLayout.WIDTH
            val y = i / SceneLayout.WIDTH
            val shade = if (busy || (x in 45..79 && y in 42..98)) {
                if ((x / 3 + y / 3) % 2 == 0) 67 else 166
            } else 126
            shade * 0x010101
        }
        return SceneLayout(pixels, null)
    }

    private fun rate(style: CompositionStyle, layout: SceneLayout = scene(), detection: DetectedSubject? = subject,
                     zoom: Float = 2.2f): StyleAssessment {
        val map = EnvironmentMap(1).apply { add(current) }
        return StyledComposition.choose(style, map, mapOf(0 to layout), zoom, detection)
    }

    private fun guide(style: CompositionStyle): SubjectBox {
        val decision = rate(style).decision
        assertTrue(decision.toString(), decision is EnvironmentDecision.Guide)
        return (decision as EnvironmentDecision.Guide).crop
    }

    private fun relative(point: ScenePoint, frame: SubjectBox) = ScenePoint(
        (point.x - frame.left) / (frame.right - frame.left),
        (point.y - frame.top) / (frame.bottom - frame.top))

    @Test fun `style changes subject placement while leaving room around the whole subject`() {
        val position = ScenePoint(.435f, .365f)
        val center = guide(CompositionStyle.CENTERED)
        val thirds = guide(CompositionStyle.THIRDS)
        val space = guide(CompositionStyle.NEGATIVE_SPACE)
        assertEquals(.5f, relative(position, center).x, .095f)
        assertTrue(abs(relative(position, thirds).x - .5f) > .07f)
        assertTrue(relative(position, space).x < .35f || relative(position, space).x > .65f)
        assertNotEquals(center, thirds)
        for (box in listOf(center, thirds, space)) {
            assertTrue(box.left < subject.box.left && box.right > subject.box.right)
            assertTrue(box.top < subject.box.top && box.bottom > subject.box.bottom)
        }
    }

    @Test fun `negative space waits for a quiet background`() {
        val result = rate(CompositionStyle.NEGATIVE_SPACE, scene(busy = true))
        assertTrue(result.decision.toString(), result.decision is EnvironmentDecision.ObserveMore)
        assertFalse(result.canAutoCapture)
    }

    @Test fun `wide keeps scene coverage and style plans obey device zoom`() {
        val wide = rate(CompositionStyle.WIDE).decision
        if (wide is EnvironmentDecision.Guide) assertTrue(wide.crop.right - wide.crop.left >= .799f)
        val limited = rate(CompositionStyle.CENTERED, zoom = 1f)
        assertFalse(limited.decision is EnvironmentDecision.Guide)
    }

    @Test fun `already centered subject can start the existing auto countdown`() {
        val centered = subject.copy(box = SubjectBox(.28f, .29f, .72f, .71f))
        val result = rate(CompositionStyle.CENTERED, detection = centered)
        assertTrue(result.decision is EnvironmentDecision.Keep)
        assertTrue(result.canAutoCapture)
        assertFalse(rate(CompositionStyle.CENTERED, detection = centered.copy(confidence = .49f)).canAutoCapture)
    }

    @Test fun `an unregistered selection is never replaced with a different visible subject`() {
        val map = EnvironmentMap(1).apply {
            add(current)
            add(EnvironmentView(1, 500, 1f, 2.2f, null))
        }
        val result = StyledComposition.choose(CompositionStyle.CENTERED, map, mapOf(0 to scene(), 1 to scene()),
            2.2f, subject, SelectedSubject(ScenePoint(.435f, .365f), 0))
        assertTrue(result.decision is EnvironmentDecision.ObserveMore)
        assertFalse(result.canAutoCapture)
    }

    @Test fun `selection tolerates focus exposure changes and rejects different framing`() {
        val reference = IntArray(96) { if (it % 12 < 5) 35 else 128 }
        val relit = IntArray(96) { (reference[it] * .82f + 54).toInt() }
        val shifted = IntArray(96) { if (it % 12 < 7) 82 else 158 }
        assertFalse(FrameSignature.sameAngle(reference, relit))
        assertTrue(FrameSignature.sameSelectedView(reference, relit))
        assertFalse(FrameSignature.sameSelectedView(reference, shifted))
    }
}
