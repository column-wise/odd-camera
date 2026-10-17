package com.juhyun.oddcamera.filter

/** Downsampled luma, row 0 = top of the frame, row-major, width*height bytes (0..255 unsigned). */
class LumaFrame(val luma: ByteArray, val width: Int, val height: Int, val histogram: IntArray)

/** threshold: absolute luma threshold actually used, 0..256 (256 = nothing passes; ruling R3). */
interface FrameAnalysis { val threshold: Int }

/**
 * A preview/photo/video effect. Adding a filter = implement this + one line in [FilterRegistry].
 * analyze() runs on the GL thread once per frame with a params snapshot; `previous` is null on the
 * first frame after the filter was (re)selected.
 */
interface Filter {
    val id: String
    val title: String
    val params: List<FilterParam>
    fun analyze(frame: LumaFrame, params: ParamValues, previous: FrameAnalysis?): FrameAnalysis
    /** Called on the GL thread; the caller calls init() before the first draw. */
    fun createRenderer(): FilterRenderer
}

interface FilterRenderer {
    fun init()
    /** srcTex = GL_TEXTURE_2D RGBA of the upright, cropped, (mirrored) frame; draws into currently bound target of size w×h. */
    fun draw(srcTex: Int, w: Int, h: Int, params: ParamValues, analysis: FrameAnalysis)
    fun release()
}
