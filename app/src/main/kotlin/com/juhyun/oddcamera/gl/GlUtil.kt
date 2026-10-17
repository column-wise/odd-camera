package com.juhyun.oddcamera.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    /** Full-screen triangle strip in clip space; shaders derive uv = pos * 0.5 + 0.5. */
    val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    /** Plain full-screen quad vertex shader: vUv 0..1, (0,0) = texture origin. Filters may reuse it. */
    const val QUAD_VS = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}"""

    /** Copies uTex (unit 0). */
    const val COPY_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 outColor;
void main() { outColor = texture(uTex, vUv); }"""

    /** Draws QUAD with the program's `aPos` attribute; program must be in use. */
    fun drawQuad(p: GlProgram) {
        val pos = p.attrib("aPos")
        GLES30.glEnableVertexAttribArray(pos)
        GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, 0, QUAD)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(pos)
    }

    fun checkGlError(op: String) {
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "$op: glError 0x${Integer.toHexString(err)}" }
    }

    fun createOesTexture(): Int = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

    fun createTexture(target: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(target, t[0])
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        checkGlError("createTexture")
        return t[0]
    }

    /** RGBA8 texture + framebuffer. */
    class Fbo(val width: Int, val height: Int) {
        val tex = createTexture(GLES30.GL_TEXTURE_2D)
        val id: Int

        init {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            val f = IntArray(1)
            GLES30.glGenFramebuffers(1, f, 0)
            id = f[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, tex, 0)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: 0x${Integer.toHexString(status)}" }
        }

        fun release() {
            GLES30.glDeleteFramebuffers(1, intArrayOf(id), 0)
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        }
    }

    /** Largest rect with the frame's aspect centered in target (letterbox, never stretch): x, y, w, h. */
    fun aspectFit(frameW: Int, frameH: Int, targetW: Int, targetH: Int): IntArray {
        val scale = minOf(targetW.toFloat() / frameW, targetH.toFloat() / frameH)
        val w = Math.round(frameW * scale)
        val h = Math.round(frameH * scale)
        return intArrayOf((targetW - w) / 2, (targetH - h) / 2, w, h)
    }
}
