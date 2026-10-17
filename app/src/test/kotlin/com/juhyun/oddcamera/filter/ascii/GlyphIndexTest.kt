package com.juhyun.oddcamera.filter.ascii

import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphIndexTest {
    @Test fun maskModeMapsAboveThresholdAcrossRamp() {
        assertEquals(9, glyphIndex(255, 100, 10))
        assertEquals(0, glyphIndex(101, 100, 10))
    }

    @Test fun outOfRangeClamps() {
        assertEquals(0, glyphIndex(50, 100, 10))
        assertEquals(9, glyphIndex(400, 100, 10))
        assertEquals(0, glyphIndex(255, 255, 5))   // no division by zero at the top (R3: 256 too)
        assertEquals(0, glyphIndex(255, 256, 5))
        assertEquals(0, glyphIndex(255, 100, 1))
    }

    @Test fun fullFrameThresholdMinusOneSpansWholeLumaRange() {
        assertEquals(0, glyphIndex(0, -1, 10))
        assertEquals(9, glyphIndex(255, -1, 10))
        assertEquals(5, glyphIndex(128, -1, 10))
    }
}

class ResolveRampTest {
    private val noInk: (String) -> Float = { 0f }

    @Test fun presetSplitsByCodePoint() {
        assertEquals(listOf(" ", "░", "▒", "▓", "█"), resolveRamp("blocks", "", noInk))
        assertEquals(" .:-=+*#%@".map { it.toString() }, resolveRamp("classic", "", noInk))
    }

    @Test fun customSortedByInkWithLeadingSpace() {
        val ink = mapOf("." to 0.1f, "a" to 0.4f, "@" to 0.8f)
        assertEquals(listOf(" ", ".", "a", "@"), resolveRamp("custom", "@a.", { ink.getValue(it) }))
    }

    @Test fun emptyCustomFallsBackToClassic() {
        assertEquals(resolveRamp("classic", "", noInk), resolveRamp("custom", "", noInk))
        assertEquals(resolveRamp("classic", "", noInk), resolveRamp("custom", "  ", noInk))
    }

    @Test fun emojiAndHangulAreSingleCodePoints() {
        assertEquals(listOf(" ", "😀", "가"), resolveRamp("custom", "😀😀가", noInk))
    }
}
