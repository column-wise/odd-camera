package com.juhyun.oddcamera.filter

/**
 * Downsampled luma, row 0 = top of the frame, row-major, width*height bytes (0..255 unsigned).
 * rgba: the same frame as image-order RGBA, rgbaWidth × rgbaHeight (aspect kept); null unless the filter
 * asked for it via [Filter.needsRgba].
 */
class LumaFrame(val luma: ByteArray, val width: Int, val height: Int, val histogram: IntArray,
                val rgba: ByteArray? = null, val rgbaWidth: Int = 0, val rgbaHeight: Int = 0)

/** threshold: absolute luma threshold actually used, 0..256 (256 = nothing passes; ruling R3). */
interface FrameAnalysis { val threshold: Int }

/** An analysis holding frame geometry; photo capture rotates it with the output (threshold-only ones need not). */
interface RotatableAnalysis : FrameAnalysis {
    /** Same analysis in the frame rotated clockwise by deg (0/90/180/270). */
    fun rotated(deg: Int): FrameAnalysis
}

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
    /** True if analyze()/captureAnalysis() with these params need [LumaFrame.rgba] (costs an extra readback). */
    fun needsRgba(params: ParamValues): Boolean = false
    /**
     * Photo capture: adapt the shutter-moment analysis to the still (whose exposure/tone differs from the preview
     * stream). `still` is the luma of the still in output orientation; `snapshot` is already rotated to it.
     * Default: keep the snapshot exactly (e.g. Light Boxes: the boxes on screen at the shutter).
     */
    fun captureAnalysis(still: LumaFrame, params: ParamValues, snapshot: FrameAnalysis): FrameAnalysis = snapshot

    /** Called on the GL thread; the caller calls init() before the first draw. */
    fun createRenderer(): FilterRenderer
}

interface FilterRenderer {
    fun init()
    /** srcTex = GL_TEXTURE_2D RGBA of the upright, cropped, (mirrored) frame; draws into currently bound target of size w×h. */
    fun draw(srcTex: Int, w: Int, h: Int, params: ParamValues, analysis: FrameAnalysis)
    fun release()
}
