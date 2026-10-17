package com.juhyun.oddcamera.render

import android.opengl.GLES30
import com.juhyun.oddcamera.filter.FilterRenderer
import com.juhyun.oddcamera.filter.FrameAnalysis
import com.juhyun.oddcamera.filter.ParamValues

/** The one "draw filtered frame into a target" step shared by preview, photo (Task 9) and encoder (Task 10). */
object FilterPass {
    fun render(renderer: FilterRenderer, srcTex: Int, targetFbo: Int, w: Int, h: Int,
               params: ParamValues, analysis: FrameAnalysis) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        renderer.draw(srcTex, w, h, params, analysis)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
}
