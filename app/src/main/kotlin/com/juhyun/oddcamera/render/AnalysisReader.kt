package com.juhyun.oddcamera.render

import android.opengl.GLES30
import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.lumaHistogram
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Downsamples the frame into a small FBO (max-pooled luma in R) and reads it back synchronously
 * (ruling R7: no PBO). GL thread only; create with a current context.
 */
class AnalysisReader {
    private val program = GlProgram(GlUtil.QUAD_VS, LUMA_FS)
    private var fbo: GlUtil.Fbo? = null
    private var pixels: ByteBuffer? = null
    private var flip: GlProgram? = null
    private var rgbaFbo: GlUtil.Fbo? = null
    private var rgbaPixels: ByteBuffer? = null

    /**
     * Returns the luma of frameTex (row 0 = top), analysisWidth wide, height keeping the frame aspect.
     * rgbaWidth > 0 also reads the frame as image-order RGBA at that width (bilinear, aspect kept) into [LumaFrame.rgba].
     */
    fun read(frameTex: Int, frameW: Int, frameH: Int, analysisWidth: Int, rgbaWidth: Int = 0): LumaFrame {
        val w = analysisWidth
        val h = maxOf(1, Math.round(w.toFloat() * frameH / frameW))
        val f = fbo?.takeIf { it.width == w && it.height == h } ?: run {
            fbo?.release()
            pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GlUtil.Fbo(w, h).also { fbo = it }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f.id)
        GLES30.glViewport(0, 0, w, h)
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLES30.glUniform2i(program.uniform("uSrc"), frameW, frameH)
        GLES30.glUniform2i(program.uniform("uDst"), w, h)
        GlUtil.drawQuad(program)
        val buf = pixels!!.apply { clear() }
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlUtil.checkGlError("analysis read")

        val luma = ByteArray(w * h)
        for (i in luma.indices) luma[i] = buf.get(i * 4)
        if (rgbaWidth <= 0) return LumaFrame(luma, w, h, lumaHistogram(luma))
        val rh = maxOf(1, Math.round(rgbaWidth.toFloat() * frameH / frameW))
        return LumaFrame(luma, w, h, lumaHistogram(luma), readRgba(frameTex, rgbaWidth, rh), rgbaWidth, rh)
    }

    private fun readRgba(frameTex: Int, w: Int, h: Int): ByteArray {
        val f = rgbaFbo?.takeIf { it.width == w && it.height == h } ?: run {
            rgbaFbo?.release()
            rgbaPixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GlUtil.Fbo(w, h).also { rgbaFbo = it }
        }
        val p = flip ?: GlProgram(GlUtil.QUAD_VS, FLIP_FS).also { flip = it }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f.id)
        GLES30.glViewport(0, 0, w, h)
        p.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GlUtil.drawQuad(p)
        val buf = rgbaPixels!!.apply { clear() }
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlUtil.checkGlError("analysis rgba read")
        return ByteArray(w * h * 4).also { buf.get(it) }
    }

    fun release() {
        fbo?.release()
        fbo = null
        rgbaFbo?.release()
        rgbaFbo = null
        flip?.release()
        flip = null
        program.release()
    }

    private companion object {
        // Frame FBO row 0 = bottom; readback row 0 = frame top (image order).
        const val FLIP_FS = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 outColor;
void main() { outColor = vec4(texture(uTex, vec2(vUv.x, 1.0 - vUv.y)).rgb, 1.0); }"""

        // Max-pool: each analysis pixel = brightest texel (exact texelFetch) of the frame block it covers, so a
        // 1-2 px lamp never vanishes or blinks with sub-pixel shake (bilinear/averaging would dim it).
        // Output row y (FBO bottom = readPixels row 0) covers the frame's y-th block from the TOP.
        // ponytail: per-axis cap of 32 taps (frame ≤ 32× analysis width; lowest tier 1080→80 is 13.5×);
        // beyond that the block is strided and a 1 px lamp can be skipped. Early break keeps normal tiers cheap.
        const val LUMA_FS = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform ivec2 uSrc;
uniform ivec2 uDst;
out vec4 outColor;
const int MAX_TAPS = 32;
void main() {
    ivec2 o = ivec2(gl_FragCoord.xy);
    ivec2 lo = o * uSrc / uDst;
    ivec2 hi = max((o + 1) * uSrc / uDst, lo + 1);
    ivec2 step = max((hi - lo + MAX_TAPS - 1) / MAX_TAPS, ivec2(1));
    float m = 0.0;
    for (int j = 0; j < MAX_TAPS; j++) {
        int k = lo.y + j * step.y;          // row counted from the frame's top
        if (k >= hi.y) break;
        for (int i = 0; i < MAX_TAPS; i++) {
            int x = lo.x + i * step.x;
            if (x >= hi.x) break;
            vec3 c = texelFetch(uTex, ivec2(x, uSrc.y - 1 - k), 0).rgb;
            m = max(m, dot(c, vec3(0.299, 0.587, 0.114)));
        }
    }
    outColor = vec4(m, 0.0, 0.0, 1.0);
}"""
    }
}
