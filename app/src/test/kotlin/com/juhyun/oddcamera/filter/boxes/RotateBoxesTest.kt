package com.juhyun.oddcamera.filter.boxes

import org.junit.Assert.assertEquals
import org.junit.Test

class RotateBoxesTest {
    private val b = Box(0.1f, 0.2f, 0.3f, 0.1f, 7, true)

    private fun check(expected: Box, actual: Box) {
        assertEquals(expected.x, actual.x, 1e-6f); assertEquals(expected.y, actual.y, 1e-6f)
        assertEquals(expected.w, actual.w, 1e-6f); assertEquals(expected.h, actual.h, 1e-6f)
        assertEquals(expected.id, actual.id); assertEquals(expected.mark, actual.mark)
    }

    // Clockwise rotation of the frame: point (x, y) -> 90: (1-y, x), 180: (1-x, 1-y), 270: (y, 1-x).
    @Test fun rotatesClockwise() {
        check(b, rotateBoxes(listOf(b), 0).single())
        check(Box(0.7f, 0.1f, 0.1f, 0.3f, 7, true), rotateBoxes(listOf(b), 90).single())
        check(Box(0.6f, 0.7f, 0.3f, 0.1f, 7, true), rotateBoxes(listOf(b), 180).single())
        check(Box(0.2f, 0.6f, 0.1f, 0.3f, 7, true), rotateBoxes(listOf(b), 270).single())
    }

    @Test fun fourQuarterTurnsAreIdentity() {
        var r = listOf(b)
        repeat(4) { r = rotateBoxes(r, 90) }
        check(b, r.single())
    }

    @Test fun analysisRotatesItsBoxes() {
        val a = LightBoxesAnalysis(200, listOf(b)).rotated(90) as LightBoxesAnalysis
        assertEquals(200, a.threshold)
        check(Box(0.7f, 0.1f, 0.1f, 0.3f, 7, true), a.boxes.single())
    }
}
