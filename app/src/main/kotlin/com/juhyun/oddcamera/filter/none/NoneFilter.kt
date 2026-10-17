package com.juhyun.oddcamera.filter.none

import android.opengl.GLES30
import com.juhyun.oddcamera.filter.Filter
import com.juhyun.oddcamera.filter.FilterParam
import com.juhyun.oddcamera.filter.FilterRenderer
import com.juhyun.oddcamera.filter.FrameAnalysis
import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil

/** Plain camera image. */
object NoneFilter : Filter {
    override val id = "none"
    override val title = "None"
    override val params = emptyList<FilterParam>()

    private val ANALYSIS = object : FrameAnalysis { override val threshold = 256 }

    override fun analyze(frame: LumaFrame, params: ParamValues, previous: FrameAnalysis?) = ANALYSIS

    override fun createRenderer(): FilterRenderer = object : FilterRenderer {
        private lateinit var program: GlProgram
        override fun init() { program = GlProgram(GlUtil.QUAD_VS, GlUtil.COPY_FS) }
        override fun draw(srcTex: Int, w: Int, h: Int, params: ParamValues, analysis: FrameAnalysis) {
            program.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GlUtil.drawQuad(program)
        }
        override fun release() = program.release()
    }
}
