package com.juhyun.oddcamera.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AspectFitTest {
    @Test fun portrait4x3InTallScreenLetterboxesTopBottom() =
        assertArrayEquals(intArrayOf(0, 300, 1080, 1440), GlUtil.aspectFit(1080, 1440, 1080, 2040))

    @Test fun wideFrameInNarrowTargetLetterboxesSides() =
        assertArrayEquals(intArrayOf(25, 0, 150, 100), GlUtil.aspectFit(300, 200, 200, 100))
}
