package com.juhyun.oddcamera.filter.boxes

import android.opengl.GLES30
import com.juhyun.oddcamera.filter.Filter
import com.juhyun.oddcamera.filter.FilterParam
import com.juhyun.oddcamera.filter.FilterRenderer
import com.juhyun.oddcamera.filter.FloatParam
import com.juhyun.oddcamera.filter.FrameAnalysis
import com.juhyun.oddcamera.filter.IntParam
import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.RotatableAnalysis
import com.juhyun.oddcamera.filter.percentileThreshold
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class LightBoxesAnalysis(override val threshold: Int, val boxes: List<Box>) : RotatableAnalysis {
    override fun rotated(deg: Int) = LightBoxesAnalysis(threshold, rotateBoxes(boxes, deg))
}

/** Normalized boxes in the frame rotated clockwise by deg: point (x, y) -> 90: (1-y, x), 180: (1-x, 1-y), 270: (y, 1-x). */
fun rotateBoxes(boxes: List<Box>, deg: Int): List<Box> = boxes.map { b ->
    when (Math.floorMod(deg, 360)) {
        90 -> b.copy(x = 1 - b.y - b.h, y = b.x, w = b.h, h = b.w)
        180 -> b.copy(x = 1 - b.x - b.w, y = 1 - b.y - b.h)
        270 -> b.copy(x = b.y, y = 1 - b.x - b.w, w = b.h, h = b.w)
        else -> b
    }
}

/** Thin white outlines (some with a small filled mark) around the brightest regions. */
object LightBoxesFilter : Filter {
    override val id = "light_boxes"
    override val title = "광원 박스"
    override val params: List<FilterParam> = listOf(
        FloatParam("topPercent", "밝은 부분 상위 %", 0.2f, 20f, 3f, 0.2f),
        IntParam("maxBoxes", "최대 박스 수", 5, 150, 40),
    )

    // Single tracker (ruling R5); analyze() runs on the GL thread only.
    private val tracker = BoxTracker()

    override fun analyze(frame: LumaFrame, params: ParamValues, previous: FrameAnalysis?): FrameAnalysis {
        if (previous == null) tracker.reset()
        val t = percentileThreshold(frame.histogram, params.float("topPercent"))
        if (t >= 256) {
            tracker.reset()
            return LightBoxesAnalysis(t, emptyList())
        }
        val blobs = detectBlobs(frame.luma, frame.width, frame.height, t)
        return LightBoxesAnalysis(t, tracker.update(blobs, frame.width, frame.height, params.int("maxBoxes")))
    }

    override fun createRenderer(): FilterRenderer = object : FilterRenderer {
        private lateinit var copy: GlProgram
        private lateinit var line: GlProgram
        private var buf: FloatBuffer = ByteBuffer.allocateDirect(0).asFloatBuffer()

        override fun init() {
            copy = GlProgram(GlUtil.QUAD_VS, GlUtil.COPY_FS)
            line = GlProgram(LINE_VS, LINE_FS)
        }

        override fun draw(srcTex: Int, w: Int, h: Int, params: ParamValues, analysis: FrameAnalysis) {
            copy.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GlUtil.drawQuad(copy)
            val boxes = (analysis as? LightBoxesAnalysis)?.boxes ?: return
            if (boxes.isEmpty()) return

            val need = boxes.size * RECTS_PER_BOX * FLOATS_PER_RECT
            if (buf.capacity() < need) buf = ByteBuffer.allocateDirect(need * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.clear()
            val lw = maxOf(1f, 0.0025f * w)
            val m = 0.006f * w
            // Pixel rect, y measured from the frame TOP (Box convention) -> clip space (GL y is up).
            fun rect(x: Float, y: Float, rw: Float, rh: Float) {
                val x0 = x / w * 2 - 1; val x1 = (x + rw) / w * 2 - 1
                val y0 = 1 - y / h * 2; val y1 = 1 - (y + rh) / h * 2
                buf.put(floatArrayOf(x0, y0, x1, y0, x0, y1, x1, y0, x1, y1, x0, y1))
            }
            for (b in boxes) {
                val x = b.x * w; val y = b.y * h; val bw = b.w * w; val bh = b.h * h
                rect(x, y, bw, lw)
                rect(x, y + bh - lw, bw, lw)
                rect(x, y + lw, lw, maxOf(0f, bh - 2 * lw))
                rect(x + bw - lw, y + lw, lw, maxOf(0f, bh - 2 * lw))
                // Fixed 5 rects per box keeps the buffer sizing trivial; unmarked boxes get a degenerate one.
                if (b.mark) rect(x + bw / 2 - m / 2, y + bh / 2 - m / 2, m, m) else rect(0f, 0f, 0f, 0f)
            }
            buf.flip()
            line.use()
            val pos = line.attrib("aPos")
            GLES30.glEnableVertexAttribArray(pos)
            GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, 0, buf)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, need / 2)
            GLES30.glDisableVertexAttribArray(pos)
        }

        override fun release() { copy.release(); line.release() }
    }

    private const val RECTS_PER_BOX = 5
    private const val FLOATS_PER_RECT = 12

    private const val LINE_VS = """#version 300 es
in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }"""
    private const val LINE_FS = """#version 300 es
precision mediump float;
out vec4 outColor;
void main() { outColor = vec4(1.0); }"""
}
