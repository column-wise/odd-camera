package com.juhyun.oddcamera.filter.ascii

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.SystemClock
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
import com.juhyun.oddcamera.filter.RotatableAnalysis
import com.juhyun.oddcamera.filter.TextParam
import com.juhyun.oddcamera.filter.percentileThreshold
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil
import java.nio.ByteBuffer

/** Dark→bright ramps; a ramp's first glyph is drawn for the dimmest cells. Every ramp starts with a blank. */
private val PRESETS = mapOf(
    "classic" to " .:-=+*#%@",
    "dots" to " ·•●",
    "blocks" to " ░▒▓█",
    "binary" to " 01",
)

/** A glyph set rather than a ramp: ink-sorted like custom so full-frame still ramps. */
private const val SYMBOLS = "#%@=+*$&"

private fun String.codePointStrings(): List<String> =
    codePoints().toArray().map { String(Character.toChars(it)) }

/**
 * Glyph ramp for the chosen set. Symbols/custom: distinct non-blank code points sorted by [inkOf] ascending,
 * after a leading space; nothing usable → classic. Region modes draw random picks from ramp[1..] (no blanks).
 */
fun resolveRamp(glyphSet: String, custom: String, inkOf: (String) -> Float): List<String> {
    val set = when (glyphSet) { "symbols" -> SYMBOLS; "custom" -> custom; else -> null }
    if (set != null) {
        val glyphs = set.codePoints().toArray()
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

/** glyphEpoch: re-roll clock ([glyphEpoch]) of the analyzed frame, so captures draw the preview's glyphs. */
class AsciiAnalysis(override val threshold: Int, val mask: Mask?, val glyphEpoch: Int) : RotatableAnalysis {
    override fun rotated(deg: Int) = AsciiAnalysis(threshold, mask?.let { rotateMask(it, deg) }, glyphEpoch)
}

fun glyphEpoch(timeMs: Long): Int = (timeMs / 250).toInt()

private const val REROLL_EPOCHS = 10

private fun mix32(v: Int): Int {
    var x = v
    x = x xor (x ushr 16); x *= 0x7feb352d
    x = x xor (x ushr 15); x *= 0x846ca68b.toInt()
    return x xor (x ushr 16)
}

/**
 * Index into a glyph set of setSize for a cell. Each cell re-rolls once every [REROLL_EPOCHS] epochs at its own
 * phase, so ~1/10 of cells re-roll per epoch (a few times per second overall). Mirrored bit-for-bit in [AsciiFilter.FS].
 */
fun glyphFor(cellX: Int, cellY: Int, epoch: Int, setSize: Int): Int {
    val h = mix32((cellX * 73856093) xor (cellY * 19349663))
    val gen = (epoch + (h ushr 8) % REROLL_EPOCHS) / REROLL_EPOCHS
    return (mix32(h xor mix32(gen)) ushr 8) % setSize
}

/**
 * Region mode: cells over people (ML Kit segmentation) or over the bright parts become red random glyphs on white,
 * the rest becomes 4-level posterized gray with a soft white glow just outside the person region. Full-frame mode: brightness-ramped glyphs everywhere.
 */
object AsciiFilter : Filter {
    override val id = "ascii"
    override val title = "아스키"
    override val params: List<FilterParam> = listOf(
        ChoiceParam("region", "영역 기준", listOf("person" to "사람", "bright" to "밝은 부분"), "person"),
        FloatParam("topPercent", "밝은 부분 상위 %", 1f, 60f, 25f, 1f, visibleWhen = "region" to "bright"),
        IntParam("columns", "가로 문자 수", 30, 160, 60),
        BoolParam("fullFrame", "전체 화면", false),
        ChoiceParam("glyphSet", "문자 종류", listOf(
            "symbols" to "기호", "classic" to "클래식", "dots" to "점", "blocks" to "블록", "binary" to "이진",
            "custom" to "직접 입력"), "symbols"),
        TextParam("customGlyphs", "직접 입력 문자", "", maxCodePoints = 16),
        BoolParam("grayBackground", "배경 단순화", true),
    )

    /** GL thread only (analyze / captureAnalysis / renderer release). Created lazily, dropped on renderer release. */
    private var segmenter: PersonSegmenter? = null
    private fun segmenter() = segmenter ?: PersonSegmenter().also { segmenter = it }

    override fun needsRgba(params: ParamValues) = params.string("region") == "person" && !params.bool("fullFrame")

    override fun analyze(frame: LumaFrame, params: ParamValues, previous: FrameAnalysis?): FrameAnalysis {
        val epoch = glyphEpoch(SystemClock.uptimeMillis())
        if (!needsRgba(params)) return AsciiAnalysis(percentileThreshold(frame.histogram, params.float("topPercent")), null, epoch)
        val seg = segmenter()
        frame.rgba?.let { seg.submit(it, frame.rgbaWidth, frame.rgbaHeight) }
        return AsciiAnalysis(256, seg.latest, epoch)
    }

    /**
     * Person mode: segment the still itself (exposure/resolution-correct mask); failure keeps the snapshot's mask.
     * Bright mode (R13): same relative threshold on the still's own histogram -> same coverage as the preview despite
     * exposure; the preview's on/off decision (contrast gate, threshold 256) wins. The glyph epoch always stays.
     */
    override fun captureAnalysis(still: LumaFrame, params: ParamValues, snapshot: FrameAnalysis): FrameAnalysis {
        val s = snapshot as? AsciiAnalysis ?: return snapshot
        if (needsRgba(params)) {
            val rgba = still.rgba ?: return s
            val m = segmenter().segmentBlocking(rgba, still.rgbaWidth, still.rgbaHeight) ?: return s
            return AsciiAnalysis(s.threshold, m, s.glyphEpoch)
        }
        if (s.threshold >= 256) return s
        val t = percentileThreshold(still.histogram, params.float("topPercent"))
        return if (t < 256) AsciiAnalysis(t, s.mask, s.glyphEpoch) else s
    }

    override fun createRenderer(): FilterRenderer = object : FilterRenderer {
        private lateinit var program: GlProgram
        private var atlas = 0
        private var maskTex = 0
        private var uploaded: Mask? = null
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
            maskTex = GlUtil.createTexture(GLES30.GL_TEXTURE_2D)
        }

        /** Mask as an R8 texture, t = 0 = frame top; re-uploaded only when the Mask instance changes. */
        private fun uploadMask(m: Mask) {
            if (m === uploaded) return
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, m.w, m.h, 0, GLES30.GL_RED,
                GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(m.data))
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            uploaded = m
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
            val a = analysis as? AsciiAnalysis
            val mask = a?.mask
            val mode = when {
                params.bool("fullFrame") -> 0
                params.string("region") == "person" -> 2
                else -> 1
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            if (mask != null) uploadMask(mask)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            program.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GLES30.glUniform1i(program.uniform("uTex"), 0)
            GLES30.glUniform1i(program.uniform("uAtlas"), 1)
            GLES30.glUniform1i(program.uniform("uMask"), 2)
            GLES30.glUniform2f(program.uniform("uSize"), w.toFloat(), h.toFloat())
            // Short side: the portrait preview's width, which stays the short side of a landscape (rotated) capture.
            GLES30.glUniform1f(program.uniform("uCell"), minOf(w, h).toFloat() / params.int("columns"))
            GLES30.glUniform1i(program.uniform("uThreshold"), analysis.threshold)
            GLES30.glUniform1i(program.uniform("uRamp"), ramp.size)
            GLES30.glUniform1i(program.uniform("uMode"), mode)
            GLES30.glUniform1i(program.uniform("uHasMask"), if (mask != null) 1 else 0)
            GLES30.glUniform1i(program.uniform("uGray"), if (params.bool("grayBackground")) 1 else 0)
            GLES30.glUniform1i(program.uniform("uEpoch"), a?.glyphEpoch ?: 0)
            GlUtil.drawQuad(program)
        }

        override fun release() {
            program.release()
            GLES30.glDeleteTextures(2, intArrayOf(atlas, maskTex), 0)
            uploaded = null
            segmenter?.close()
            segmenter = null
        }
    }

    /** Atlas cell size in px (square). */
    private const val CELL = 64

    // Cells are square, min(w, h) / columns px (preview: columns across its width), counted from the frame's top-left (GL y is up, so flip).
    // Cell luma = average of 4 taps inside the cell. uMode 0 = full frame (glyphIndex() above with threshold -1),
    // 1 = bright cells (luma > threshold), 2 = person cells (mask at the cell centre > 0.5).
    // Region cells: white + red glyph ramp[1 + glyphFor()]. Outside: posterized gray (uGray) or src, then the glow.
    // Posterize: 3×3 box blur of luma with taps min(w, h) / 180 px apart (resolution-independent) -> floor(l / 64) * 64 + 32,
    // i.e. flat 32/96/160/224 bands. Glow (person mode): white fading over 1 glyph cell from the nearest neighbouring
    // region cell (binarized mask), so low-confidence areas never glow.
    // uMask t = 0 is the frame top.
    // textureGrad keeps the atlas mip level smooth across cell edges (fract() would spike it).
    private const val FS = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform sampler2D uAtlas;
uniform sampler2D uMask;
uniform vec2 uSize;
uniform float uCell;
uniform int uThreshold;
uniform int uRamp;
uniform int uMode;
uniform bool uHasMask;
uniform bool uGray;
uniform int uEpoch;
in vec2 vUv;
out vec4 outColor;
const vec3 LUMA = vec3(0.299, 0.587, 0.114);
uint mix32(uint x) {
    x ^= x >> 16; x *= 0x7feb352du;
    x ^= x >> 15; x *= 0x846ca68bu;
    return x ^ (x >> 16);
}
uint cellHash(vec2 c) { return mix32((uint(int(c.x)) * 73856093u) ^ (uint(int(c.y)) * 19349663u)); }
bool personAt(vec2 cell) { return texture(uMask, (cell + 0.5) * uCell / uSize).r > 0.5; }
vec3 srcTop(vec2 p) { return texture(uTex, vec2(p.x / uSize.x, 1.0 - p.y / uSize.y)).rgb; }
float glyph(int idx, vec2 local) {
    float n = float(uRamp);
    return textureGrad(uAtlas, vec2((float(idx) + local.x) / n, local.y),
                       vec2(1.0 / (n * uCell), 0.0), vec2(0.0, 1.0 / uCell)).a;
}
void main() {
    vec2 g = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y) / uCell;
    vec2 cell = floor(g);
    vec2 local = g - cell;
    vec3 c = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec2 q = (cell + vec2(i % 2 == 0 ? 0.25 : 0.75, i < 2 ? 0.25 : 0.75)) * uCell;
        c += srcTop(q);
    }
    c *= 0.25;
    int l = int(dot(c, LUMA) * 255.0 + 0.5);
    if (uMode == 0) {
        int idx = clamp(l * uRamp / 256, 0, uRamp - 1);
        outColor = vec4(c * glyph(idx, local), 1.0);
        return;
    }
    bool person = uMode == 2 && uHasMask;
    if (person ? personAt(cell) : uMode == 1 && l > uThreshold) {
        uint h = cellHash(cell);
        uint gen = (uint(uEpoch) + (h >> 8) % 10u) / 10u;
        int idx = 1 + int((mix32(h ^ mix32(gen)) >> 8) % uint(uRamp - 1));
        outColor = vec4(mix(vec3(1.0), vec3(208.0, 20.0, 30.0) / 255.0, glyph(idx, local)), 1.0);
        return;
    }
    vec3 base = texture(uTex, vUv).rgb;
    if (uGray) {
        float r = min(uSize.x, uSize.y) / 180.0;
        vec2 p = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y);
        vec3 s = vec3(0.0);
        for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) s += srcTop(p + vec2(i, j) * r);
        float bl = min(dot(s / 9.0, LUMA) * 255.0, 255.0);
        base = vec3((floor(bl / 64.0) * 64.0 + 32.0) / 255.0);
    }
    float d = 2.0;
    if (person) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
        vec2 nc = cell + vec2(dx, dy);
        if (personAt(nc)) d = min(d, length(g - clamp(g, nc, nc + 1.0)));
    }
    outColor = vec4(mix(base, vec3(1.0), 1.0 - smoothstep(0.0, 1.0, d)), 1.0);
}"""
}
