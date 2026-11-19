package dev.cameracompositor

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveShotHandoffTest {
    private val pattern = IntArray(96) { 40 + it % 12 * 11 + it / 12 * 4 }
    private val source = LiveScene(100, pattern)

    @Test fun `a stationary view can show a shot after the old overlap deadline`() {
        val live = LiveScene(1480, pattern.copyOf())
        assertEquals(ShotHandoff.SHOW, LiveShotHandoff.assess(source, live, 1f, 1f, 1500))
    }

    @Test fun `exposure adjustment can preserve the view`() {
        val live = LiveScene(900, IntArray(96) { (pattern[it] * .75f + 9).toInt() })
        assertEquals(ShotHandoff.SHOW, LiveShotHandoff.assess(source, live, .65f, .65f, 920))
    }

    @Test fun `shifted image requires feature matching before any live outline appears`() {
        val other = LiveScene(900, IntArray(96) { if (it / 12 < 4) 160 else 25 })
        assertEquals(ShotHandoff.VERIFY, LiveShotHandoff.assess(source, other, 1f, 1f, 920))
    }

    @Test fun `rescan after a zoom change, stale frame, or expired source`() {
        val live = LiveScene(900, pattern.copyOf())
        assertEquals(ShotHandoff.RESCAN, LiveShotHandoff.assess(source, live, 1f, 1.08f, 920))
        assertEquals(ShotHandoff.RESCAN, LiveShotHandoff.assess(source, live, 1f, 1f, 1500))
        assertEquals(ShotHandoff.RESCAN, LiveShotHandoff.assess(source, LiveScene(47, pattern), 1f, 1f, 120))
        assertEquals(ShotHandoff.RESCAN, LiveShotHandoff.assess(source, LiveScene(7600, pattern), 1f, 1f, 7620))
    }
}
