package com.juhyun.oddcamera.filter.ascii

import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.lumaHistogram
import com.juhyun.oddcamera.filter.percentileThreshold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AsciiCaptureAnalysisTest {
    private val params = ParamValues(AsciiFilter.params).apply { set("region", "bright") }
    private fun snap(t: Int) = AsciiAnalysis(t, null, 7)

    /** 100 px: a 0..198 ramp (step 2), scaled by `gain`. */
    private fun still(gain: Float): LumaFrame {
        val luma = ByteArray(100) { (it * 2 * gain).toInt().toByte() }
        return LumaFrame(luma, 10, 10, lumaHistogram(luma))
    }

    @Test fun usesTheStillsOwnPercentile() {
        val s = still(0.7f)
        assertEquals(percentileThreshold(s.histogram, params.float("topPercent")),
            AsciiFilter.captureAnalysis(s, params, snap(150)).threshold)
    }

    @Test fun previewWithNothingReplacedStaysEmpty() {
        val none = snap(256)
        assertSame(none, AsciiFilter.captureAnalysis(still(1f), params, none))
    }

    @Test fun flatStillKeepsTheSnapshotThreshold() {
        val luma = ByteArray(100) { 10 }
        val s = snap(150)
        assertSame(s, AsciiFilter.captureAnalysis(LumaFrame(luma, 10, 10, lumaHistogram(luma)), params, s))
    }
}

class AsciiCaptureEpochTest {
    @Test fun brightCaptureKeepsTheSnapshotEpoch() {
        val params = ParamValues(AsciiFilter.params).apply { set("region", "bright") }
        val luma = ByteArray(100) { (it * 2).toByte() }
        val r = AsciiFilter.captureAnalysis(LumaFrame(luma, 10, 10, lumaHistogram(luma)), params, AsciiAnalysis(150, null, 42))
        assertEquals(42, (r as AsciiAnalysis).glyphEpoch)
    }

    @Test fun personCaptureWithoutStillRgbaKeepsTheSnapshotMask() {
        val snap = AsciiAnalysis(256, Mask(ByteArray(4), 2, 2), 3)
        val luma = ByteArray(4)
        assertSame(snap, AsciiFilter.captureAnalysis(LumaFrame(luma, 2, 2, lumaHistogram(luma)), ParamValues(AsciiFilter.params), snap))
    }
}
