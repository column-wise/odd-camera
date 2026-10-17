package com.juhyun.oddcamera.render

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.juhyun.oddcamera.filter.FilterRenderer
import com.juhyun.oddcamera.filter.RotatableAnalysis
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photo path of ruling R8: still image -> (mirror, rotate to output orientation) -> the same FilterPass the preview
 * uses, with the shutter-moment params/analysis. GL thread only; create with a current context.
 */
class PhotoRenderer {
    private val prep = GlProgram(GlUtil.QUAD_VS, PREP_FS)
    private val flip = GlProgram(GlUtil.QUAD_VS, FLIP_FS)
    private val reader = AnalysisReader()
    private val maxSize = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]

    /**
     * src: upright (sensor rotation and crop applied), never mirrored, ARGB_8888. Returns the filtered photo in
     * output orientation (rotated clockwise by snapshot.rotationDeg). `renderer` must belong to snapshot.filter.
     */
    fun render(src: Bitmap, snapshot: CaptureSnapshot, renderer: FilterRenderer): Bitmap {
        val scale = minOf(1f, maxSize.toFloat() / maxOf(src.width, src.height))
        // ponytail: rare (12 MP stills vs ≥ 8192 limits) so the downscale runs here on the GL thread.
        val img = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val rot = Math.floorMod(snapshot.rotationDeg, 360)
        val swap = rot % 180 != 0
        val w = if (swap) img.height else img.width
        val h = if (swap) img.width else img.height

        val tex = GlUtil.createTexture(GLES30.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, img, 0) // bitmap row 0 (top) at t = 0
        if (img !== src) img.recycle()
        val a = GlUtil.Fbo(w, h)
        val b = GlUtil.Fbo(w, h)
        try {
            // Source -> upright frame texture like the preview's (GL row 0 = bottom), mirrored and rotated.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, a.id)
            GLES30.glViewport(0, 0, w, h)
            prep.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glUniform1i(prep.uniform("uRot"), rot)
            GLES30.glUniform1i(prep.uniform("uMirror"), if (snapshot.mirror) 1 else 0)
            GlUtil.drawQuad(prep)
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)

            val rotated = (snapshot.analysis as? RotatableAnalysis)?.rotated(rot) ?: snapshot.analysis
            val still = reader.read(a.tex, w, h, RenderThread.ANALYSIS_WIDTH)
            val analysis = snapshot.filter.captureAnalysis(still, snapshot.params, rotated)
            FilterPass.render(renderer, a.tex, b.id, w, h, snapshot.params, analysis)

            // Flip into `a` so glReadPixels' first row is the image top (Bitmap order).
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, a.id)
            GLES30.glViewport(0, 0, w, h)
            flip.use()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, b.tex)
            GlUtil.drawQuad(flip)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GlUtil.checkGlError("photo render")
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf.rewind()) }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            a.release()
            b.release()
        }
    }

    fun release() {
        prep.release()
        flip.release()
        reader.release()
    }

    private companion object {
        // Output pixel (u, v from the top) -> preview-frame point (inverse clockwise rotation) -> source (un-mirror).
        // Must agree with rotateBoxes: frame point (x, y) -> 90: (1-y, x), 180: (1-x, 1-y), 270: (y, 1-x).
        const val PREP_FS = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform int uRot;
uniform int uMirror;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec2 p = vec2(vUv.x, 1.0 - vUv.y);
    if (uRot == 90) p = vec2(p.y, 1.0 - p.x);
    else if (uRot == 180) p = 1.0 - p;
    else if (uRot == 270) p = vec2(1.0 - p.y, p.x);
    if (uMirror == 1) p.x = 1.0 - p.x;
    outColor = vec4(texture(uTex, p).rgb, 1.0);
}"""

        const val FLIP_FS = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 outColor;
void main() { outColor = texture(uTex, vec2(vUv.x, 1.0 - vUv.y)); }"""
    }
}
