package dev.cameracompositor

import org.junit.Assert.*
import org.junit.Test

class CompatibleCompositionTest {
    private val full = SubjectBox(0f, 0f, 1f, 1f)

    private fun translation(x: Float) = ImageProjection(listOf(1f, 0f, x, 0f, 1f, 0f, 0f, 0f, 1f))

    private fun link(x: Float) = ViewRegistration(50, 60, kotlin.math.abs(x), .5f, .6f,
        translation(x), translation(-x))

    private fun sweep(vararg links: ViewRegistration?): EnvironmentMap = EnvironmentMap(1).apply {
        add(EnvironmentView(0, 1000, 1f, 2.2f, null))
        links.forEachIndexed { index, link -> add(EnvironmentView(index + 1, 1500L + index * 500, 1f, 2.2f, link, index)) }
    }

    private fun layout(shift: Float = 0f, obstructed: Boolean = false, useDepth: Boolean = false): SceneLayout {
        val pixels = IntArray(SceneLayout.WIDTH * SceneLayout.HEIGHT) { index ->
            val x = (index % SceneLayout.WIDTH + .5f) / SceneLayout.WIDTH - shift
            val y = (index / SceneLayout.WIDTH + .5f) / SceneLayout.HEIGHT
            val pattern = (index % SceneLayout.WIDTH / 3 + index / SceneLayout.WIDTH / 3) % 2 == 0
            val shade = when {
                obstructed && y > .69f -> if (pattern) 78 else 126
                x in .31f .. .69f && y in .23f .. .65f -> if (pattern) 80 else 158
                else -> 115
            }
            (shade shl 16) or (shade shl 8) or shade
        }
        val depth = if (useDepth) RelativeDepth(24, 32, FloatArray(24 * 32) { index ->
            if (obstructed && index / 24 >= 22) .9f else .35f
        }) else null
        return SceneLayout(pixels, depth)
    }

    @Test fun `regions project in both directions across registered views`() {
        val map = sweep(link(.1f), link(-.18f))
        val reference = SubjectBox(.3f, .25f, .6f, .65f)
        val projected = map.project(0, 2, reference)!!
        assertEquals(.22f, projected.left, .0001f)
        assertEquals(.52f, projected.right, .0001f)
        assertEquals(reference.left, map.project(2, 0, projected)!!.left, .0001f)
        assertNull(sweep(link(.1f), null).project(0, 2, reference))
    }

    @Test fun `near obstruction can favor an earlier real viewpoint at the same zoom`() {
        val map = sweep(link(.12f))
        val decision = ObservedComposition.choose(map, mapOf(
            0 to layout(useDepth = true), 1 to layout(.12f, true, true)), 1f)
        assertTrue(decision.toString(), decision is EnvironmentDecision.Guide)
        decision as EnvironmentDecision.Guide
        assertEquals(0, decision.viewId)
        assertEquals(full, decision.crop)
    }

    @Test fun `missing depth still scores the current view and keeps a stable framing at fixed zoom`() {
        val decision = ObservedComposition.choose(sweep(link(.002f)), mapOf(0 to layout(), 1 to layout()), 1f)
        assertTrue(decision.toString(), decision is EnvironmentDecision.Keep)
    }

    @Test fun `missing overlap cannot send the user to a previous viewpoint`() {
        val decision = ObservedComposition.choose(sweep(null), mapOf(
            0 to layout(useDepth = true), 1 to layout(.12f, true, true)), 1f)
        assertTrue(decision is EnvironmentDecision.Keep)
    }

    @Test fun `a flat low detail frame requests an observation with visible detail`() {
        val flat = SceneLayout(IntArray(SceneLayout.WIDTH * SceneLayout.HEIGHT) { 0x777777 }, null)
        assertTrue(ObservedComposition.choose(sweep(), mapOf(0 to flat), 2f) is EnvironmentDecision.ObserveMore)
    }

    @Test fun `relative depth rejects invalid maps and preserves within view order`() {
        assertNull(RelativeDepth.fromOutput(FloatArray(8 * 8), 8, 8))
        assertNull(RelativeDepth.fromOutput(FloatArray(24 * 32) { 1f }, 24, 32))
        assertNull(RelativeDepth.fromOutput(FloatArray(24 * 32) { Float.NaN }, 24, 32))
        val ramp = RelativeDepth.fromOutput(FloatArray(24 * 32) { it / 24f }, 24, 32)!!
        assertTrue(ramp.at(.5f, .2f) < ramp.at(.5f, .8f))
        assertEquals(64, ramp.grid8x8().size)
    }

    @Test fun `depth evidence needs two connected observations with compatible order`() {
        val ramp = RelativeDepth(24, 32, FloatArray(24 * 32) { (it / 24) / 31f })
        val reversed = RelativeDepth(24, 32, FloatArray(24 * 32) { 1f - ramp.values[it] })
        val estimates = mapOf(0 to ramp, 1 to ramp)
        assertEquals(estimates, DepthAgreement.corroborated(sweep(link(.12f)), estimates))
        assertTrue(DepthAgreement.corroborated(sweep(link(.12f)), mapOf(0 to ramp, 1 to reversed)).isEmpty())
        assertTrue(DepthAgreement.corroborated(sweep(null), estimates).isEmpty())
        assertTrue(DepthAgreement.corroborated(sweep(), mapOf(0 to ramp)).isEmpty())
    }

    @Test fun `same view tolerates exposure changes and rejects a displaced edge`() {
        val width = 96
        val height = 64
        fun signature(edge: Int, brightness: Int) = FrameSignature.sample(ByteArray(width * height) {
            ((if (it % width < edge) 40 else 150) + brightness).toByte()
        }, width, height)
        val earlier = signature(40, 0)
        assertTrue(FrameSignature.sameAngle(earlier, signature(40, 13)))
        assertFalse(FrameSignature.sameAngle(earlier, signature(64, 0)))
    }
}
