package com.juhyun.oddcamera.render

import android.graphics.Color
import android.opengl.GLES30
import java.nio.ByteBuffer

/** Reads a w×h RGBA framebuffer as ARGB ints in image order (index = y * w + x, row 0 = top of frame). */
fun readImage(fbo: Int, w: Int, h: Int): IntArray {
    val buf = ByteBuffer.allocateDirect(w * h * 4)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
    GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    return IntArray(w * h) { i ->
        val o = ((h - 1 - i / w) * w + i % w) * 4     // GL row 0 = bottom
        val b = { k: Int -> buf.get(o + k).toInt() and 0xFF }
        Color.argb(b(3), b(0), b(1), b(2))
    }
}

/** Mean abs difference of the RGB-average after box-downscaling both images to 90 px wide (widths divisible by 90). */
fun downscaledMad(a: IntArray, wa: Int, ha: Int, b: IntArray, wb: Int, hb: Int): Double {
    fun down(p: IntArray, w: Int, h: Int): DoubleArray {
        val f = w / 90
        return DoubleArray(90 * (h / f)) { i ->
            val x = i % 90; val y = i / 90
            var s = 0
            for (j in 0 until f) for (k in 0 until f) {
                val c = p[(y * f + j) * w + x * f + k]
                s += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
            }
            s.toDouble() / (f * f)
        }
    }
    val da = down(a, wa, ha); val db = down(b, wb, hb)
    return da.indices.sumOf { Math.abs(da[it] - db[it]) } / da.size
}
