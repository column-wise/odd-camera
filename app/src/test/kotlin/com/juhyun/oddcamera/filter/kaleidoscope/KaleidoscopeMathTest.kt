package com.juhyun.oddcamera.filter.kaleidoscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaleidoscopeMathTest {
    private fun f(x: Float, y: Float, w: Float = 1080f, h: Float = 1440f) = shiftFraction(x, y, w, h, 0.8f, 1.5f)

    @Test fun fullShiftAtCenter() = assertEquals(0.8f, f(540f, 720f), 1e-6f)

    @Test fun noShiftAtCorners() {
        for ((x, y) in listOf(0f to 0f, 1080f to 0f, 0f to 1440f, 1080f to 1440f)) assertEquals(0f, f(x, y), 1e-6f)
    }

    @Test fun monotoneAlongARay() {
        val v = (0..20).map { f(540f + it * 27f, 720f + it * 36f) }
        assertTrue(v.toString(), v.zipWithNext().all { (a, b) -> b < a })
    }

    @Test fun symmetric() {
        assertEquals(f(300f, 500f), f(780f, 940f), 1e-6f)
        assertEquals(f(300f, 500f), f(780f, 500f), 1e-6f)
        assertEquals(f(300f, 500f), f(300f, 940f), 1e-6f)
    }

    @Test fun rotationInvariant() = assertEquals(f(300f, 500f), f(500f, 300f, 1440f, 1080f), 1e-6f)

    @Test fun cellSizeUsesShortSide() {
        assertEquals(180f, cellSize(1080, 1440, 6), 1e-6f)
        assertEquals(180f, cellSize(1440, 1080, 6), 1e-6f)
    }
}
