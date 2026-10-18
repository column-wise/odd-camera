package com.juhyun.oddcamera.render

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.kaleidoscope.KaleidoscopeFilter
import com.juhyun.oddcamera.gl.EglCore
import com.juhyun.oddcamera.gl.GlUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KaleidoscopeRenderTest {
    private lateinit var egl: EglCore

    @Before fun setUp() {
        egl = EglCore()
        egl.makeCurrent(egl.createOffscreenSurface(1, 1))
    }

    @After fun tearDown() = egl.release()

    /** Black 3:4 frame (360 units wide; s = width / 360): 12-unit white squares at the center and in the top-left corner. */
    private fun scene(width: Int): Bitmap {
        val h = width * 4 / 3; val s = width / 360
        val px = IntArray(width * h) { i ->
            val x = i % width / s; val y = i / width / s
            if ((x in 174 until 186 && y in 234 until 246) || (x in 2 until 14 && y in 2 until 14)) Color.WHITE else Color.BLACK
        }
        return Bitmap.createBitmap(px, width, h, Bitmap.Config.ARGB_8888)
    }

    private fun render(width: Int): Pair<IntArray, Int> {
        val src = scene(width)
        val h = src.height
        val tex = uploadUpright(src)
        val r = KaleidoscopeFilter.createRenderer().apply { init() }
        val out = GlUtil.Fbo(width, h)
        val params = ParamValues(KaleidoscopeFilter.params)
        FilterPass.render(r, tex, out.id, width, h, params, KaleidoscopeFilter.analyze(LumaFrame(ByteArray(1), 1, 1, IntArray(256)), params, null))
        GlUtil.checkGlError("render")
        val px = readImage(out.id, width, h)
        r.release(); out.release()
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        return px to h
    }

    /** Defaults: 6 cells over 360 -> 60 px cells, center cell shifted by 0.8 * 60 = 48 px. */
    @Test fun centerSquareBecomesFourTranslucentCopies() {
        val (px, _) = render(360)
        fun v(x: Int, y: Int) = Color.green(px[y * 360 + x])
        for ((x, y) in listOf(180 to 240, 228 to 240, 180 to 288, 228 to 288))
            assertTrue("copy at ($x,$y) = ${v(x, y)}", v(x, y) in 40..200)
        // Away from the copies everything stays black.
        for ((x, y) in listOf(204 to 240, 180 to 264, 204 to 264, 252 to 240, 180 to 312, 156 to 240, 180 to 216))
            assertTrue("($x,$y) = ${v(x, y)}", v(x, y) < 10)
    }

    @Test fun cornerSquareIsUnchanged() {
        val (px, _) = render(360)
        for (y in 0 until 20) for (x in 0 until 20) {
            val want = if (x in 2 until 14 && y in 2 until 14) 255 else 0
            assertEquals("($x,$y)", want.toDouble(), Color.green(px[y * 360 + x]).toDouble(), 3.0)
        }
    }

    @Test fun resolutionIndependent() {
        val (a, ha) = render(360)
        val (b, hb) = render(1440)
        val mad = downscaledMad(a, 360, ha, b, 1440, hb)
        assertTrue("mean abs diff $mad >= 3", mad < 3)
    }
}
