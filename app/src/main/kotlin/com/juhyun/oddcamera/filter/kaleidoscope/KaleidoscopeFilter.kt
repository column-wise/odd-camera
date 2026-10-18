package com.juhyun.oddcamera.filter.kaleidoscope

import android.opengl.GLES30
import com.juhyun.oddcamera.filter.Filter
import com.juhyun.oddcamera.filter.FilterParam
import com.juhyun.oddcamera.filter.FilterRenderer
import com.juhyun.oddcamera.filter.FloatParam
import com.juhyun.oddcamera.filter.FrameAnalysis
import com.juhyun.oddcamera.filter.IntParam
import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/** Cell edge in px: the short side (portrait preview width) / cells, so rotated captures keep the preview's grid. */
fun cellSize(w: Int, h: Int, cells: Int): Float = minOf(w, h).toFloat() / cells

/** Shift as a fraction of a cell for a cell centered at (x, y): maxShift at the frame center, 0 at the corners. */
fun shiftFraction(cellCenterX: Float, cellCenterY: Float, w: Float, h: Float, maxShift: Float, falloff: Float): Float {
    val d = hypot(cellCenterX - w / 2, cellCenterY - h / 2) / hypot(w / 2, h / 2)
    return maxShift * max(0f, 1f - d).pow(falloff)
}

/** Each grid cell drawn 4× (as is, shifted right, down, down-right), averaged; copies spill over neighbors. */
object KaleidoscopeFilter : Filter {
    override val id = "kaleidoscope"
    override val title = "Kaleidoscope"
    override val params: List<FilterParam> = listOf(
        IntParam("cells", "칸 개수", 3, 16, 6),
        FloatParam("maxShift", "최대 밀림", 0f, 1f, 0.8f, 0.05f),
        FloatParam("falloff", "중앙 집중도", 0.5f, 4f, 1.5f, 0.1f),
    )

    private val ANALYSIS = object : FrameAnalysis { override val threshold = 256 }

    override fun analyze(frame: LumaFrame, params: ParamValues, previous: FrameAnalysis?) = ANALYSIS

    override fun createRenderer(): FilterRenderer = object : FilterRenderer {
        private lateinit var program: GlProgram
        override fun init() { program = GlProgram(GlUtil.QUAD_VS, FS) }
        override fun draw(srcTex: Int, w: Int, h: Int, params: ParamValues, analysis: FrameAnalysis) {
            program.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GLES30.glUniform1i(program.uniform("uTex"), 0)
            GLES30.glUniform2f(program.uniform("uSize"), w.toFloat(), h.toFloat())
            GLES30.glUniform1f(program.uniform("uCell"), cellSize(w, h, params.int("cells")))
            GLES30.glUniform1f(program.uniform("uMaxShift"), params.float("maxShift"))
            GLES30.glUniform1f(program.uniform("uFalloff"), params.float("falloff"))
            GlUtil.drawQuad(program)
        }
        override fun release() = program.release()
    }

    // Image coords (y down). Grid anchored so the frame center is a cell center. Shifts are in [0, 1] cell, so a
    // copy from cell C covers only C and its right/down/down-right neighbors: p gathers from own, left, up, up-left.
    private const val FS = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
uniform float uCell;
uniform float uMaxShift;
uniform float uFalloff;
out vec4 outColor;
vec2 cellOf(vec2 q, vec2 ctr) { return floor((q - ctr) / uCell + 0.5); }
void main() {
    vec2 p = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y);
    vec2 ctr = uSize * 0.5;
    vec2 own = cellOf(p, ctr);
    vec3 sum = vec3(0.0);
    float n = 0.0;
    for (int c = 0; c < 4; c++) {
        vec2 ci = own - vec2(float(c % 2), float(c / 2));
        float d = length(ci * uCell) / length(ctr);
        float o = uMaxShift * pow(max(0.0, 1.0 - d), uFalloff) * uCell;
        for (int k = 0; k < 4; k++) {
            vec2 q = p - vec2(float(k % 2), float(k / 2)) * o;
            if (cellOf(q, ctr) == ci) {
                sum += texture(uTex, vec2(q.x / uSize.x, 1.0 - q.y / uSize.y)).rgb;
                n += 1.0;
            }
        }
    }
    outColor = vec4(sum / n, 1.0);
}"""
}
