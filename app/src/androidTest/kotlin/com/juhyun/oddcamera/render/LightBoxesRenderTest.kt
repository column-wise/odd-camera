package com.juhyun.oddcamera.render

import android.graphics.Color
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.boxes.LightBoxesAnalysis
import com.juhyun.oddcamera.filter.boxes.LightBoxesFilter
import com.juhyun.oddcamera.filter.lumaHistogram
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
class LightBoxesRenderTest {
    private lateinit var egl: EglCore
    private var black = 0

    @Before fun setUp() {
        egl = EglCore()
        egl.makeCurrent(egl.createOffscreenSurface(1, 1))
        black = GlUtil.createTexture(GLES30.GL_TEXTURE_2D)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 4, 4, 0, GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(64))
    }

    @After fun tearDown() = egl.release()

    /** 160x120 luma, row 0 = top: a 10x10 square in the top-left quadrant, a 16x16 one (stays under the split area) at lower right. */
    private fun frame(): LumaFrame {
        val w = 160; val h = 120
        val luma = ByteArray(w * h)
        fun sq(x: Int, y: Int, s: Int) { for (j in y until y + s) for (i in x until x + s) luma[j * w + i] = -1 }
        sq(20, 10, 10); sq(100, 70, 16)
        return LumaFrame(luma, w, h, lumaHistogram(luma))
    }

    /** Returns ARGB pixels in image order (row 0 = top of frame) and the height. */
    private fun render(width: Int): Pair<IntArray, Int> {
        val h = width * 3 / 4
        val params = ParamValues(LightBoxesFilter.params)
        val analysis = LightBoxesFilter.analyze(frame(), params, null) as LightBoxesAnalysis
        assertEquals(2, analysis.boxes.size)
        val r = LightBoxesFilter.createRenderer().apply { init() }
        val out = GlUtil.Fbo(width, h)
        FilterPass.render(r, black, out.id, width, h, params, analysis)
        GlUtil.checkGlError("render")
        val px = readImage(out.id, width, h)
        r.release(); out.release()
        return px to h
    }

    @Test fun boxesLandWhereTheBlobsAreNotFlipped() {
        val (px, h) = render(360)
        fun any(x0: Int, y0: Int, x1: Int, y1: Int) =
            (y0 until y1).any { y -> (x0 until x1).any { x -> Color.red(px[y * 360 + x]) > 128 } }
        assertTrue("outline missing top-left", any(0, 0, 180, h / 2))
        assertTrue("outline missing lower-right", any(180, h / 2, 360, h))
        assertTrue("unexpected outline top-right", !any(180, 0, 360, h / 2))
        assertTrue("unexpected outline bottom-left", !any(0, h / 2, 180, h))
    }

    @Test fun resolutionIndependent() {
        val (a, ha) = render(360)
        val (b, hb) = render(1440)
        val mad = downscaledMad(a, 360, ha, b, 1440, hb)
        assertTrue("mean abs diff $mad >= 6", mad < 6)
    }
}
