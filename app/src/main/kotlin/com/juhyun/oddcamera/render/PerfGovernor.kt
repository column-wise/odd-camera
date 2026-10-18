package com.juhyun.oddcamera.render

data class Tier(val analysisWidth: Int, val processScale: Float)

/**
 * Picks a quality tier from frame times. Windows are non-overlapping blocks of [windowFrames]: one tier down per
 * window averaging > 30 ms, one tier up after 3 consecutive windows averaging < 20 ms. Frozen = input ignored.
 */
class PerfGovernor(private val tiers: List<Tier> = DEFAULT_TIERS, private val windowFrames: Int = 30) {
    var index = 0
        private set
    val tier get() = tiers[index]
    private var sum = 0f
    private var count = 0
    private var fastWindows = 0
    private var frozen = false

    fun freeze(on: Boolean) {
        frozen = on
        sum = 0f; count = 0; fastWindows = 0 // a window never spans a freeze
    }

    fun onFrame(frameTimeMs: Float): Tier {
        if (frozen) return tier
        sum += frameTimeMs
        if (++count < windowFrames) return tier
        val avg = sum / count
        sum = 0f; count = 0
        when {
            avg > 30f -> { fastWindows = 0; if (index < tiers.lastIndex) index++ }
            avg < 20f -> if (++fastWindows >= 3) { fastWindows = 0; if (index > 0) index-- }
            else -> fastWindows = 0
        }
        return tier
    }

    companion object {
        val DEFAULT_TIERS = listOf(Tier(160, 1.0f), Tier(120, 0.75f), Tier(80, 0.5f))
    }
}
