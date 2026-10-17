package com.juhyun.oddcamera.filter.ascii

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.juhyun.oddcamera.filter.BoolParam
import com.juhyun.oddcamera.filter.ChoiceParam
import com.juhyun.oddcamera.filter.Filter
import com.juhyun.oddcamera.filter.FilterParam
import com.juhyun.oddcamera.filter.FilterRenderer
import com.juhyun.oddcamera.filter.FloatParam
import com.juhyun.oddcamera.filter.FrameAnalysis
import com.juhyun.oddcamera.filter.IntParam
import com.juhyun.oddcamera.filter.LumaFrame
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.TextParam
import com.juhyun.oddcamera.filter.percentileThreshold
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil

/** Dark→bright ramps; a ramp's first glyph is drawn for the dimmest cells. */
private val PRESETS = mapOf(
    "classic" to " .:-=+*#%@",
    "dots" to " ·•●",
    "blocks" to " ░▒▓█",
    "binary" to " 01",
)

private fun String.codePointStrings(): List<String> =
    codePoints().toArray().map { String(Character.toChars(it)) }

/**
 * Glyph ramp for the chosen set. Custom: distinct non-blank code points sorted by [inkOf] ascending,
 * after a leading space; nothing usable → classic.
 */
fun resolveRamp(glyphSet: String, custom: String, inkOf: (String) -> Float): List<String> {
    if (glyphSet == "custom") {
        val glyphs = custom.codePoints().toArray()
            .filter { !Character.isWhitespace(it) && !Character.isISOControl(it) }
            .distinct().map { String(Character.toChars(it)) }
        if (glyphs.isNotEmpty()) return listOf(" ") + glyphs.sortedBy(inkOf)
    }
    return (PRESETS[glyphSet] ?: PRESETS.getValue("classic")).codePointStrings()
}

/**
 * Ramp index for a cell of [luma] (0..255): lumas above [threshold] spread across the ramp, brighter → denser.
 * Full-frame mode passes threshold = -1 to spread the whole 0..255 range. Mirrored in [AsciiFilter.FS].
 */
fun glyphIndex(luma: Int, threshold: Int, rampSize: Int): Int =
    ((luma - threshold - 1) * rampSize / maxOf(1, 255 - threshold)).coerceIn(0, maxOf(0, rampSize - 1))

/** Grid of glyph cells over the bright parts (mask mode) or the whole frame (full-frame mode). */
object AsciiFilter : Filter {
    override val id = "ascii"
    override val title = "아스키"
    override val params: List<FilterParam> = listOf(
        FloatParam("topPercent", "밝은 부분 상위 %", 1f, 60f, 25f, 1f),
        IntParam("columns", "가로 문자 수", 30, 160, 60),
        BoolParam("fullFrame", "전체 화면", false),
        ChoiceParam("glyphSet", "문자 종류", listOf(
            "classic" to "클래식", "dots" to "점", "blocks" to "블록", "binary" to "이진",
            "custom" to "직접 입력"), "classic"),
        TextParam("customGlyphs", "직접 입력 문자", "", maxCodePoints = 16),
    )

    override fun analyze(frame: LumaFrame, params: ParamValues, previous: FrameAnalysis?): FrameAnalysis {
        val t = percentileThreshold(frame.histogram, params.float("topPercent"))
        return object : FrameAnalysis { override val threshold = t }
    }

    /**
     * Same relative threshold on the still's own histogram -> same coverage as the preview despite exposure (R13).
     * The preview's on/off decision (contrast gate, threshold 256) wins: a still that crosses the gate differently
     * keeps the snapshot.
     */
    override fun captureAnalysis(still: LumaFrame, params: ParamValues, snapshot: FrameAnalysis): FrameAnalysis =
        if (snapshot.threshold >= 256) snapshot
        else analyze(still, params, null).takeIf { it.threshold < 256 } ?: snapshot

    override fun createRenderer(): FilterRenderer = object : FilterRenderer {
        private lateinit var program: GlProgram
        private var atlas = 0
        private var ramp: List<String> = emptyList()
        private val inkCache = HashMap<String, Float>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = CELL * 0.8f
        }

        override fun init() {
            program = GlProgram(GlUtil.QUAD_VS, FS)
            atlas = GlUtil.createTexture(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        }

        private fun drawGlyph(canvas: Canvas, g: String, cx: Float) {
            val fm = paint.fontMetrics
            canvas.drawText(g, cx, CELL / 2f - (fm.ascent + fm.descent) / 2f, paint)
        }

        private fun inkOf(g: String): Float = inkCache.getOrPut(g) {
            val bmp = Bitmap.createBitmap(CELL, CELL, Bitmap.Config.ARGB_8888)
            drawGlyph(Canvas(bmp), g, CELL / 2f)
            val px = IntArray(CELL * CELL).also { bmp.getPixels(it, 0, CELL, 0, 0, CELL, CELL) }
            bmp.recycle()
            px.count { Color.alpha(it) != 0 }.toFloat() / px.size
        }

        /** 1 row × ramp.size glyph cells, white on transparent; bitmap row 0 = texture t 0 = cell top. */
        private fun rebuildAtlas(r: List<String>) {
            val bmp = Bitmap.createBitmap(CELL * r.size, CELL, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            r.forEachIndexed { i, g -> drawGlyph(canvas, g, (i + 0.5f) * CELL) }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            bmp.recycle()
            ramp = r
        }

        override fun draw(srcTex: Int, w: Int, h: Int, params: ParamValues, analysis: FrameAnalysis) {
            val r = resolveRamp(params.string("glyphSet"), params.string("customGlyphs"), ::inkOf)
            if (r != ramp) rebuildAtlas(r)
            program.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GLES30.glUniform1i(program.uniform("uTex"), 0)
            GLES30.glUniform1i(program.uniform("uAtlas"), 1)
            GLES30.glUniform2f(program.uniform("uSize"), w.toFloat(), h.toFloat())
            GLES30.glUniform1f(program.uniform("uCell"), w.toFloat() / params.int("columns"))
            GLES30.glUniform1i(program.uniform("uThreshold"), analysis.threshold)
            GLES30.glUniform1i(program.uniform("uRamp"), ramp.size)
            GLES30.glUniform1i(program.uniform("uFull"), if (params.bool("fullFrame")) 1 else 0)
            GlUtil.drawQuad(program)
        }

        override fun release() {
            program.release()
            GLES30.glDeleteTextures(1, intArrayOf(atlas), 0)
        }
    }

    /** Atlas cell size in px (square). */
    private const val CELL = 64

    // Cells are square, w / columns px, counted from the frame's top-left (GL y is up, so flip).
    // Cell luma = average of 4 taps inside the cell; glyph index = glyphIndex() above.
    // textureGrad keeps the atlas mip level smooth across cell edges (fract() would spike it).
    private const val FS = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform sampler2D uAtlas;
uniform vec2 uSize;
uniform float uCell;
uniform int uThreshold;
uniform int uRamp;
uniform bool uFull;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec2 g = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y) / uCell;
    vec2 cell = floor(g);
    vec2 local = g - cell;
    vec3 c = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec2 q = (cell + vec2(i % 2 == 0 ? 0.25 : 0.75, i < 2 ? 0.25 : 0.75)) * uCell;
        c += texture(uTex, vec2(q.x / uSize.x, 1.0 - q.y / uSize.y)).rgb;
    }
    c *= 0.25;
    int l = int(dot(c, vec3(0.299, 0.587, 0.114)) * 255.0 + 0.5);
    int t = uFull ? -1 : uThreshold;
    if (l <= t) { outColor = texture(uTex, vUv); return; }
    int idx = clamp((l - t - 1) * uRamp / max(1, 255 - t), 0, uRamp - 1);
    float n = float(uRamp);
    float a = textureGrad(uAtlas, vec2((float(idx) + local.x) / n, local.y),
                          vec2(1.0 / (n * uCell), 0.0), vec2(0.0, 1.0 / uCell)).a;
    if (uFull) {
        outColor = vec4(c * a, 1.0);
    } else {
        vec3 ink = (int(cell.x) + int(cell.y)) % 3 == 0 ? vec3(0.8, 0.2, 0.2) : vec3(85.0 / 255.0);
        outColor = vec4(mix(vec3(244.0, 240.0, 255.0) / 255.0, ink, a), 1.0);
    }
}"""
}
