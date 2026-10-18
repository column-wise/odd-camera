package com.juhyun.oddcamera.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PerfGovernorTest {
    private fun PerfGovernor.feed(n: Int, ms: Float) = repeat(n) { onFrame(ms) }

    @Test fun slowWindowStepsDownThenFastWindowsStepBackUp() {
        val g = PerfGovernor()
        g.feed(29, 40f); assertEquals(0, g.index)
        g.feed(1, 40f); assertEquals(1, g.index)
        g.feed(60, 15f); assertEquals(1, g.index)   // only 2 fast windows so far
        g.feed(30, 15f); assertEquals(0, g.index)
    }

    @Test fun clampsAtBothEnds() {
        val g = PerfGovernor()
        g.feed(30 * 5, 50f); assertEquals(2, g.index)
        g.feed(30 * 9, 10f); assertEquals(0, g.index)
        g.feed(30 * 3, 10f); assertEquals(0, g.index)
    }

    @Test fun middlingWindowResetsTheFastStreak() {
        val g = PerfGovernor()
        g.feed(30, 40f)
        g.feed(60, 15f); g.feed(30, 25f); g.feed(60, 15f)
        assertEquals(1, g.index)
        g.feed(30, 15f); assertEquals(0, g.index)
    }

    @Test fun frozenIgnoresInput() {
        val g = PerfGovernor()
        g.freeze(true)
        g.feed(300, 100f); assertEquals(0, g.index)
        g.freeze(false)
        g.feed(30, 100f); assertEquals(1, g.index)
        g.freeze(true)
        g.feed(300, 5f); assertEquals(1, g.index)
    }
}
