package com.juhyun.oddcamera.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdTest {
    @Test fun top3PercentOfRampSelectsBrightestTail() {
        val luma = ByteArray(1000) { (it * 256 / 1000).toByte() }
        val t = percentileThreshold(lumaHistogram(luma), 3f)
        val passing = luma.count { (it.toInt() and 0xFF) > t }
        assertTrue(passing in 20..30)
    }

    @Test fun uniformFrameYieldsNothing() {
        val luma = ByteArray(1000) { 200.toByte() }
        assertEquals(256, percentileThreshold(lumaHistogram(luma), 3f))
    }

    @Test fun emptyHistogramYieldsNothing() {
        assertEquals(256, percentileThreshold(IntArray(256), 3f))
    }

    @Test fun histogramTreatsBytesAsUnsigned() {
        val h = lumaHistogram(byteArrayOf(0, (-1).toByte(), (-1).toByte()))
        assertEquals(1, h[0]); assertEquals(2, h[255])
    }
}
