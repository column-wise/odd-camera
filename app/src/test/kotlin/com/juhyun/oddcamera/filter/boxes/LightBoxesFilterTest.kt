package com.juhyun.oddcamera.filter.boxes

import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.lumaHistogram
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.concurrent.thread

class LightBoxesFilterTest {
    /** 40×40 black frame with an 8×8 white square at (x, 10). */
    private fun frame(x: Int): LumaFrame {
        val l = ByteArray(1600)
        for (y in 10 until 18) for (i in x until x + 8) l[y * 40 + i] = -1
        return LumaFrame(l, 40, 40, lumaHistogram(l))
    }

    private fun <T> on(block: () -> T): T {
        var r: T? = null
        thread { r = block() }.join()
        @Suppress("UNCHECKED_CAST") return r as T
    }

    /** Activity recreation: a second render thread must not inherit (or disturb) the first one's tracker state. */
    @Test fun trackerStateIsPerThread() {
        val params = ParamValues(LightBoxesFilter.params).apply { set("topPercent", 5f) }
        val fresh = on { (LightBoxesFilter.analyze(frame(13), params, null) as LightBoxesAnalysis).boxes }
        on { LightBoxesFilter.analyze(frame(10), params, null) } // the other thread tracks a nearby box
        val mine = on { (LightBoxesFilter.analyze(frame(13), params, LightBoxesAnalysis(0, emptyList())) as LightBoxesAnalysis).boxes }
        assertEquals(fresh, mine)
    }
}
