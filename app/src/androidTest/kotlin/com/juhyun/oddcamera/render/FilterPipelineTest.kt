package com.juhyun.oddcamera.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES30
import android.opengl.GLUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.none.NoneFilter
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
class FilterPipelineTest {
    private lateinit var egl: EglCore
    private lateinit var input: Bitmap
    private var tex = 0

    @Before fun setUp() {
        egl = EglCore()
        egl.makeCurrent(egl.createOffscreenSurface(1, 1))
        // 320x240 black with a white 20x20 square at the center (even-aligned so a 2:1 downsample is exact).
        input = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            Canvas(this).drawRect(150f, 110f, 170f, 130f, Paint().apply { color = Color.WHITE })
        }
        tex = GlUtil.createTexture(GLES30.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, input, 0)
    }

    @After fun tearDown() = egl.release()

    @Test fun analysisHistogramCountsTheBrightSquare() {
        val reader = AnalysisReader()
        val frame = reader.read(tex, W, H, 160)
        assertEquals(160, frame.width)
        assertEquals(120, frame.height)
        assertEquals(160 * 120, frame.luma.size)
        val top = frame.histogram[255]
        assertTrue("top bin $top not ≈ 100", top in 80..120)
        assertEquals(160 * 120, frame.histogram.sum())
        reader.release()
    }

    /** 1280x960 -> 160: 8x8 texel blocks. A 2x2 lamp must survive at every sub-block offset (max-pool). */
    @Test fun smallBrightDotNeverVanishes() {
        val reader = AnalysisReader()
        val dotTex = GlUtil.createTexture(GLES30.GL_TEXTURE_2D)
        val white = Paint().apply { color = Color.WHITE }
        for (dy in 0 until 8) for (dx in 0 until 8) {
            val bmp = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLACK)
                Canvas(this).drawRect(640f + dx, 480f + dy, 642f + dx, 482f + dy, white)
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dotTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            val top = reader.read(dotTex, 1280, 960, 160).histogram[255]
            assertTrue("dot at +$dx,+$dy: top bin $top", top in 1..4)
        }
        reader.release()
    }

    @Test fun noneFilterCopiesSource() {
        val params = ParamValues(NoneFilter.params)
        val analysis = NoneFilter.analyze(AnalysisReader().read(tex, W, H, 160), params, null)
        assertEquals(256, analysis.threshold)

        val renderer = NoneFilter.createRenderer().apply { init() }
        val out = GlUtil.Fbo(W, H)
        FilterPass.render(renderer, tex, out.id, W, H, params, analysis)
        GlUtil.checkGlError("render")

        assertEquals(input.getPixel(W / 2, H / 2), pixelAt(out.id, W / 2, H / 2))
        assertEquals(input.getPixel(5, 5), pixelAt(out.id, 5, 5))
        renderer.release()
        out.release()
    }

    private fun pixelAt(fbo: Int, x: Int, y: Int): Int {
        val buf = ByteBuffer.allocateDirect(4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glReadPixels(x, y, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val b = { i: Int -> buf.get(i).toInt() and 0xFF }
        return Color.argb(b(3), b(0), b(1), b(2))
    }

    private companion object { const val W = 320; const val H = 240 }
}
