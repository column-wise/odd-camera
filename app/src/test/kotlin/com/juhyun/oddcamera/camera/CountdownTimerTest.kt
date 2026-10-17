package com.juhyun.oddcamera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownTimerTest {
    private val pending = ArrayDeque<Pair<Long, () -> Unit>>()
    private val events = mutableListOf<String>()
    private fun timer(s: Int) = CountdownTimer(s, { events += "tick$it" }, { events += "done" }) { ms, f -> pending += ms to f }
    private fun runAll() { while (pending.isNotEmpty()) pending.removeFirst().let { (ms, f) -> assertEquals(1000L, ms); f() } }

    @Test fun threeSecondsTicksThenDone() {
        timer(3).start(); runAll()
        assertEquals(listOf("tick3", "tick2", "tick1", "done"), events)
    }

    @Test fun cancelStopsDone() {
        val t = timer(3); t.start()
        pending.removeFirst().second() // 1 s elapsed
        t.cancel(); runAll()
        assertEquals(listOf("tick3", "tick2"), events)
    }

    @Test fun zeroFiresDoneImmediately() {
        timer(0).start()
        assertEquals(listOf("done"), events); assertEquals(0, pending.size)
    }
}
