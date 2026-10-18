package com.juhyun.oddcamera.filter.ascii

import com.juhyun.oddcamera.filter.ParamValues
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsciiV2Test {
    private fun grid(epoch: Int) = IntArray(100 * 100) { glyphFor(it % 100, it / 100, epoch, 8) }

    @Test fun glyphForIsDeterministicAndInRange() {
        assertArrayEquals(grid(1234), grid(1234))
        val g = grid(7)
        assertTrue(g.all { it in 0 until 8 })
        assertEquals("every glyph used", 8, g.distinct().size)
        assertTrue((0 until 50).all { glyphFor(it, 3, 9, 1) == 0 })
    }

    @Test fun aSmallShareOfCellsRerollsPerEpoch() {
        for (e in listOf(0, 1, 99, 4_000_000)) {
            val a = grid(e); val b = grid(e + 1)
            val share = a.indices.count { a[it] != b[it] } / a.size.toDouble()
            assertTrue("epoch $e: $share", share in 0.05..0.20)
        }
    }

    @Test fun epochIsQuarterSeconds() {
        assertEquals(0, glyphEpoch(249))
        assertEquals(4, glyphEpoch(1000))
    }

    /** 3×2 mask, values = index: row 0 = 0 1 2, row 1 = 3 4 5. Rotation clockwise like rotateBoxes. */
    private val mask = Mask(ByteArray(6) { it.toByte() }, 3, 2)

    private fun Mask.rows() = (0 until h).map { y -> (0 until w).map { data[y * w + it].toInt() } }

    @Test fun maskRotation() {
        assertEquals(listOf(listOf(3, 0), listOf(4, 1), listOf(5, 2)), rotateMask(mask, 90).rows())
        assertEquals(listOf(listOf(5, 4, 3), listOf(2, 1, 0)), rotateMask(mask, 180).rows())
        assertEquals(listOf(listOf(2, 5), listOf(1, 4), listOf(0, 3)), rotateMask(mask, 270).rows())
        assertEquals(mask, rotateMask(mask, 0))
    }

    @Test fun analysisRotatesItsMaskAndKeepsEpoch() {
        val r = AsciiAnalysis(256, mask, 42).rotated(90) as AsciiAnalysis
        assertEquals(2, r.mask!!.w)
        assertEquals(42, r.glyphEpoch)
        assertEquals(256, r.threshold)
    }

    @Test fun regionDefaultsToPersonAndSymbolsIsDefaultSet() {
        val p = ParamValues(AsciiFilter.params)
        assertEquals("person", p.string("region"))
        assertEquals("symbols", p.string("glyphSet"))
        assertTrue(p.bool("grayBackground"))
        assertTrue(AsciiFilter.needsRgba(p))
        p.set("region", "bright")
        assertTrue(!AsciiFilter.needsRgba(p))
        assertEquals("region" to "bright", AsciiFilter.params.first { it.key == "topPercent" }.visibleWhen)
        assertEquals(null, AsciiFilter.params.first { it.key == "columns" }.visibleWhen)
    }

    @Test fun symbolsSetResolvesWithLeadingBlank() {
        assertEquals(listOf(" ", "#", "%", "@", "=", "+", "*", "$", "&"), resolveRamp("symbols", "") { 0f })
    }
}
