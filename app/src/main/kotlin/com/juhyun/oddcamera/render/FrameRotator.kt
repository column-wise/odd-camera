package com.juhyun.oddcamera.render

import android.opengl.GLES30
import com.juhyun.oddcamera.filter.FrameAnalysis
import com.juhyun.oddcamera.filter.RotatableAnalysis
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil

/**
 * Shared by photo (Task 9) and video (Task 10): source frame -> output orientation (rotated clockwise by rot),
 * optionally mirrored, as an upright frame texture (GL row 0 = bottom) ready for [FilterPass]. GL thread only.
 */
class FrameRotator {
    private val prep = GlProgram(GlUtil.QUAD_VS, PREP_FS)

    /** srcTopFirst: texture t = 0 is the image top (Bitmap upload) rather than the bottom (GL frame FBO). */
    fun draw(srcTex: Int, srcTopFirst: Boolean, rot: Int, mirror: Boolean, target: GlUtil.Fbo) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.id)
        GLES30.glViewport(0, 0, target.width, target.height)
        prep.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
        GLES30.glUniform1i(prep.uniform("uRot"), Math.floorMod(rot, 360))
        GLES30.glUniform1i(prep.uniform("uMirror"), if (mirror) 1 else 0)
        GLES30.glUniform1i(prep.uniform("uTopFirst"), if (srcTopFirst) 1 else 0)
        GlUtil.drawQuad(prep)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun release() = prep.release()

    companion object {
        /** The analysis in the rotated frame; threshold-only analyses are orientation-free. */
        fun rotate(a: FrameAnalysis, rot: Int): FrameAnalysis = (a as? RotatableAnalysis)?.rotated(Math.floorMod(rot, 360)) ?: a

        // Output pixel (u, v from the top) -> preview-frame point (inverse clockwise rotation) -> source (un-mirror).
        // Must agree with rotateBoxes: frame point (x, y) -> 90: (1-y, x), 180: (1-x, 1-y), 270: (y, 1-x).
        private const val PREP_FS = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform int uRot;
uniform int uMirror;
uniform int uTopFirst;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec2 p = vec2(vUv.x, 1.0 - vUv.y);
    if (uRot == 90) p = vec2(p.y, 1.0 - p.x);
    else if (uRot == 180) p = 1.0 - p;
    else if (uRot == 270) p = vec2(1.0 - p.y, p.x);
    if (uMirror == 1) p.x = 1.0 - p.x;
    if (uTopFirst == 0) p.y = 1.0 - p.y;
    outColor = vec4(texture(uTex, p).rgb, 1.0);
}"""
    }
}
