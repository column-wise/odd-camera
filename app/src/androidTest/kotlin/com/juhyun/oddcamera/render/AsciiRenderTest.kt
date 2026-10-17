package com.juhyun.oddcamera.render

import android.graphics.Color
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.ascii.AsciiFilter
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

    /** Image-order ARGB pixels (row 0 = top) of a width × 3/4 render, and its height. */
    private fun render(width: Int, setup: ParamValues.() -> Unit = {}): Pair<IntArray, Int> {
        val h = width * 3 / 4
        val params = ParamValues(AsciiFilter.params).apply { set("topPercent", 60f); setup() }
        val reader = AnalysisReader()
        val analysis = AsciiFilter.analyze(reader.read(src, SW, SH, 160), params, null)
        reader.release()
        assertTrue("threshold ${analysis.threshold} should let the white half pass", analysis.threshold < 255)
        val r = AsciiFilter.createRenderer().apply { init() }
        val out = GlUtil.Fbo(width, h)
        FilterPass.render(r, src, out.id, width, h, params, analysis)
        GlUtil.checkGlError("render")
        val px = readImage(out.id, width, h)
        r.release(); out.release()
        return px to h
    }

    @Test fun maskModeReplacesOnlyTheBrightTopHalf() {
        val (px, h) = render(360)
        val top = px.copyOfRange(0, 360 * (h * 3 / 8))
        assertTrue("no light background in top half", top.count { it == BG } > top.size / 4)
        assertTrue("no glyph ink in top half", top.count { Color.red(it) < 200 } > 0)
        assertTrue("top half not white anymore", top.none { it == Color.WHITE })
        for (y in h * 5 / 8 until h) for (x in 0 until 360)
            assertEquals("dark pixel ($x,$y) must be src", DARK, px[y * 360 + x])
    }

    @Test fun fullFrameTurnsDarkHalfIntoGlyphsOnBlack() {
        val (px, h) = render(360) { set("fullFrame", true) }
        val bottom = px.copyOfRange(360 * (h * 5 / 8), px.size)
        assertTrue("no black background", bottom.count { it == Color.BLACK } > bottom.size / 4)
        assertTrue("no tinted glyph", bottom.any { Color.blue(it) > Color.red(it) })
    }

    @Test fun customEmojiAndHangulRender() {
        val (px, _) = render(360) { set("glyphSet", "custom"); set("customGlyphs", "😀😀가") }
        assertTrue(px.any { it == BG })
    }

    @Test fun resolutionIndependent() {
        val (a, ha) = render(360)
        val (b, hb) = render(1440)
        val mad = downscaledMad(a, 360, ha, b, 1440, hb)
        assertTrue("mean abs diff $mad >= 6", mad < 6)
    }

    private companion object {
        const val SW = 320; const val SH = 240
        val DARK = Color.rgb(40, 80, 120)          // luma 72.6, far from a rounding edge
        val BG = Color.rgb(0xF4, 0xF0, 0xFF)
    }
}
