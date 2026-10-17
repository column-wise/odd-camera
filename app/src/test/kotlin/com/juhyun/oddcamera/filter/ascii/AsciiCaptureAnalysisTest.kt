package com.juhyun.oddcamera.filter.ascii

import com.juhyun.oddcamera.filter.FrameAnalysis
import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.lumaHistogram
import com.juhyun.oddcamera.filter.percentileThreshold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AsciiCaptureAnalysisTest {
    private val params = ParamValues(AsciiFilter.params)
    private fun snap(t: Int) = object : FrameAnalysis { override val threshold = t }

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
