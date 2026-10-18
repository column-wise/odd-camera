package com.juhyun.oddcamera.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EncoderSelectorTest {
    @Test fun portraitFirstCandidateIs1080x1920Surface() {
        val c = encoderCandidates(0)
        assertEquals(EncoderConfig(1080, 1920, 30, 12_000_000, true), c[0])
        assertEquals(EncoderConfig(720, 1280, 30, 6_000_000, true), c[1])
        assertFalse(c[2].surfaceInput)
        assertEquals(720 to 1280, c[2].width to c[2].height)
        assertEquals(c, encoderCandidates(180))
    }

    @Test fun landscapeSwapsSize() {
        val c = encoderCandidates(90)
        assertEquals(listOf(1920 to 1080, 1280 to 720, 1280 to 720), c.map { it.width to it.height })
        assertEquals(c, encoderCandidates(270))
    }

    @Test fun unsupportedSizesAreSkipped() {
        // e.g. an encoder that only accepts width ≤ 1280 and no surface input at all
        val c = encoderCandidates(0) { it.width <= 1280 && !it.surfaceInput }
        assertEquals(listOf(EncoderConfig(720, 1280, 30, 6_000_000, false)), c)
    }
}
