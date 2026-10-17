package com.juhyun.oddcamera.render

import android.opengl.GLES30
import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.lumaHistogram
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Downsamples the frame into a small FBO (luma in R) and reads it back synchronously (ruling R7: no PBO).
 * GL thread only; create with a current context.
 */
class AnalysisReader {
    private val program = GlProgram(GlUtil.QUAD_VS, LUMA_FS)
    private var fbo: GlUtil.Fbo? = null
    private var pixels: ByteBuffer? = null

    /** Returns the luma of frameTex (row 0 = top), analysisWidth wide, height keeping the frame aspect. */
    fun read(frameTex: Int, frameW: Int, frameH: Int, analysisWidth: Int): LumaFrame {
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
        GlUtil.drawQuad(program)
        val buf = pixels!!.apply { clear() }
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlUtil.checkGlError("analysis read")

        val luma = ByteArray(w * h)
        for (i in luma.indices) luma[i] = buf.get(i * 4)
        return LumaFrame(luma, w, h, lumaHistogram(luma))
    }

    fun release() {
        fbo?.release()
        fbo = null
        program.release()
    }

    private companion object {
        // Sampled with v flipped so that readPixels row 0 (FBO bottom) holds the frame's top row.
        const val LUMA_FS = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec3 c = texture(uTex, vec2(vUv.x, 1.0 - vUv.y)).rgb;
    outColor = vec4(dot(c, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
}"""
    }
}
