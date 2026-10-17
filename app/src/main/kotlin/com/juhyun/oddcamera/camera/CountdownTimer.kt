package com.juhyun.oddcamera.camera

/** Shutter self-timer: tick(seconds)…tick(1) one second apart, then done. seconds ∈ {0, 3, 10}; 0 → done at once. */
class CountdownTimer(
    private val seconds: Int,
    private val tick: (Int) -> Unit,
    private val done: () -> Unit,
    private val scheduler: (Long, () -> Unit) -> Unit,
) {
    private var cancelled = false

    fun start() = step(seconds)

    fun cancel() { cancelled = true }

    private fun step(remaining: Int) {
        if (cancelled) return
        if (remaining <= 0) return done()
        tick(remaining)
        scheduler(1000L) { step(remaining - 1) }
    }
}
