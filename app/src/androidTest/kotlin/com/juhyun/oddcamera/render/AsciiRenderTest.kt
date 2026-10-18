package com.juhyun.oddcamera.render

import android.graphics.Color
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juhyun.oddcamera.filter.FrameAnalysis
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.ascii.AsciiAnalysis
import com.juhyun.oddcamera.filter.ascii.AsciiFilter
import com.juhyun.oddcamera.filter.ascii.Mask
import com.juhyun.oddcamera.gl.EglCore
import com.juhyun.oddcamera.gl.GlUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class AsciiRenderTest {
    private lateinit var egl: EglCore
    private var src = 0

    @Before fun setUp() {
        egl = EglCore()
        egl.makeCurrent(egl.createOffscreenSurface(1, 1))
        // 320x240: frame TOP half white, bottom half DARK. Uploaded in GL row order (row 0 = frame bottom).
        val buf = ByteBuffer.allocateDirect(SW * SH * 4)
        for (r in 0 until SH) for (x in 0 until SW) {
            val c = if (r >= SH / 2) Color.WHITE else DARK
            buf.put(Color.red(c).toByte()).put(Color.green(c).toByte()).put(Color.blue(c).toByte()).put(-1)
        }
        buf.position(0)
        src = GlUtil.createTexture(GLES30.GL_TEXTURE_2D)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, SW, SH, 0, GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE, buf)
    }

    @After fun tearDown() = egl.release()

    /** Image-order ARGB pixels (row 0 = top) of a width × 3/4 render, and its height. Default: bright region. */
    private fun render(width: Int, analysis: FrameAnalysis? = null, setup: ParamValues.() -> Unit = {}): Pair<IntArray, Int> {
        val h = width * 3 / 4
        val params = ParamValues(AsciiFilter.params).apply { set("region", "bright"); set("topPercent", 60f); setup() }
        val a = analysis ?: run {
            val reader = AnalysisReader()
            AsciiFilter.analyze(reader.read(src, SW, SH, 160), params, null).also { reader.release() }
        }
        if (analysis == null && !params.bool("fullFrame"))
            assertTrue("threshold ${a.threshold} should let the white half pass", a.threshold < 255)
        val r = AsciiFilter.createRenderer().apply { init() }
        val out = GlUtil.Fbo(width, h)
        FilterPass.render(r, src, out.id, width, h, params, a)
        GlUtil.checkGlError("render")
        val px = readImage(out.id, width, h)
        r.release(); out.release()
        return px to h
    }

    /** On the white→#D0141E line: G≈B, R ≥ G. */
    private fun isRedOnWhite(c: Int) = Math.abs(Color.green(c) - Color.blue(c)) <= 14 && Color.red(c) >= Color.green(c) - 2
    private fun isRed(c: Int) = Color.red(c) - Color.green(c) > 100
    private fun isGray(c: Int, v: Int) = listOf(Color.red(c), Color.green(c), Color.blue(c)).all { Math.abs(it - v) <= 2 }

    @Test fun brightModeFillsOnlyTheBrightTopHalf() {
        val (px, h) = render(360) { set("grayBackground", false) }
        val top = px.copyOfRange(0, 360 * (h * 3 / 8))
        assertTrue("top half not red-on-white", top.all(::isRedOnWhite))
        assertTrue("no red glyphs in top half", top.count(::isRed) > top.size / 50)
        for (y in h * 5 / 8 until h) for (x in 0 until 360)
            assertEquals("dark pixel ($x,$y) must be src", DARK, px[y * 360 + x])
    }

    @Test fun brightModeGraysTheRest() {
        val (px, h) = render(360)
        for (y in h * 5 / 8 until h) for (x in 0 until 360)
            assertTrue("($x,$y) ${Integer.toHexString(px[y * 360 + x])} not gray", isGray(px[y * 360 + x], 58))
    }

    @Test fun fullFrameTurnsDarkHalfIntoGlyphsOnBlack() {
        val (px, h) = render(360) { set("fullFrame", true) }
        val bottom = px.copyOfRange(360 * (h * 5 / 8), px.size)
        assertTrue("no black background", bottom.count { it == Color.BLACK } > bottom.size / 4)
        assertTrue("no tinted glyph", bottom.any { Color.blue(it) > Color.red(it) })
    }

    @Test fun customEmojiAndHangulRender() {
        val (px, _) = render(360) { set("glyphSet", "custom"); set("customGlyphs", "😀😀가") }
        assertTrue(px.any { it == Color.WHITE })
        assertTrue(px.any(::isRed))
    }

    @Test fun resolutionIndependent() {
        val (a, ha) = render(360)
        val (b, hb) = render(1440)
        val mad = downscaledMad(a, 360, ha, b, 1440, hb)
        assertTrue("mean abs diff $mad >= 6", mad < 6)
    }

    /** Synthetic person mask: frame's top-LEFT quadrant (catches x and y flips). */
    private val quadrant = AsciiAnalysis(256, Mask(ByteArray(32 * 24) { i -> if (i % 32 < 16 && i / 32 < 12) -1 else 0 }, 32, 24), 5)

    private fun personRender(width: Int, gray: Boolean) = render(width, quadrant) { set("region", "person"); set("grayBackground", gray) }

    @Test fun personMaskFillsItsCellsAndGraysTheRest() {
        val w = 360
        val (px, h) = personRender(w, gray = true)
        val m = 24 // > 2 cells (6 px) + mask texel (11 px) from the mask edge
        var red = 0
        for (y in m until h / 2 - m) for (x in m until w / 2 - m) {
            val c = px[y * w + x]
            assertTrue("($x,$y) ${Integer.toHexString(c)} not red-on-white", isRedOnWhite(c))
            if (isRed(c)) red++
        }
        assertTrue("no glyphs in the person region", red > 50)
        for (y in 0 until h) for (x in 0 until w) {
            if (x < w / 2 + m && y < h / 2 + m || Math.abs(y - h / 2) <= 2) continue // region/glow, src seam
            val expect = if (y < h / 2) 204 else 58 // luma × 0.8
            assertTrue("($x,$y) ${Integer.toHexString(px[y * w + x])} not gray $expect", isGray(px[y * w + x], expect))
        }
        // Soft glow: just outside the right edge of the region, brighter than the plain gray further right.
        val edge = px[(h / 4) * w + w / 2 + 2]
        assertTrue("no glow at the edge: ${Integer.toHexString(edge)}", Color.red(edge) > 204 + 10)
    }

    @Test fun personModeWithoutGrayKeepsSrcOutside() {
        val w = 360
        val (px, h) = personRender(w, gray = false)
        for (y in 0 until h) for (x in 0 until w) {
            if (x < w / 2 + 24 && y < h / 2 + 24 || Math.abs(y - h / 2) <= 2) continue
            assertEquals("($x,$y) must be src", if (y < h / 2) Color.WHITE else DARK, px[y * w + x])
        }
    }

    @Test fun personModeWithoutMaskHasNoRegion() {
        val (px, _) = render(360, AsciiAnalysis(256, null, 5)) { set("region", "person") }
        assertTrue(px.none(::isRed))
    }

    @Test fun personModeResolutionIndependent() {
        val (a, ha) = personRender(360, true)
        val (b, hb) = personRender(1440, true)
        val mad = downscaledMad(a, 360, ha, b, 1440, hb)
        assertTrue("mean abs diff $mad >= 6", mad < 6)
    }

    private companion object {
        const val SW = 320; const val SH = 240
        val DARK = Color.rgb(40, 80, 120)          // luma 72.6, far from a rounding edge
    }
}
