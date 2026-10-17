package com.juhyun.oddcamera.filter.boxes

import org.junit.Assert.assertEquals
import org.junit.Test

class BlobDetectorTest {
    @Test fun twoSeparateSpotsGiveTwoBlobs() {
        val w = 10; val h = 10; val l = ByteArray(100)
        l[11] = -1; l[12] = -1; l[77] = -1; l[78] = -1   // 0xFF
        val b = detectBlobs(l, w, h, threshold = 200)
        assertEquals(2, b.size); assertEquals(Blob(1, 1, 2, 1, 2), b.first { it.y0 == 1 })
    }

    @Test fun minAreaDropsSinglePixelsAndSortsByAreaDesc() {
        val l = ByteArray(100)
        l[0] = -1                       // 1px, dropped
        l[55] = -1; l[56] = -1; l[57] = -1
        l[91] = -1; l[92] = -1
        assertEquals(listOf(3, 2), detectBlobs(l, 10, 10, 200).map { it.area })
    }
}
