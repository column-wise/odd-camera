package com.juhyun.oddcamera.record

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat

data class EncoderConfig(val width: Int, val height: Int, val fps: Int, val bitrate: Int, val surfaceInput: Boolean)

/**
 * Ordered fallbacks: 1080p surface -> 720p surface -> 720p buffer input. Portrait (rotation 0/180) swaps to w < h.
 * `isSupported` drops candidates no encoder can take (see [avcEncoderFor]); JVM tests pass a fake.
 */
fun encoderCandidates(rotationDeg: Int, isSupported: (EncoderConfig) -> Boolean = { true }): List<EncoderConfig> {
    val portrait = Math.floorMod(rotationDeg, 180) == 0
    fun cfg(w: Int, h: Int, bitrate: Int, surface: Boolean) =
        if (portrait) EncoderConfig(h, w, 30, bitrate, surface) else EncoderConfig(w, h, 30, bitrate, surface)
    return listOf(
        cfg(1920, 1080, 12_000_000, true),
        cfg(1280, 720, 6_000_000, true),
        cfg(1280, 720, 6_000_000, false),
    ).filter(isSupported)
}

/** Name of the first H.264 encoder that takes this size and input kind (hardware encoders list first), or null. */
fun avcEncoderFor(c: EncoderConfig): String? {
    val color = if (c.surfaceInput) CodecCapabilities.COLOR_FormatSurface else CodecCapabilities.COLOR_FormatYUV420Flexible
    return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } && runCatching {
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            caps.videoCapabilities?.isSizeSupported(c.width, c.height) == true && color in caps.colorFormats
        }.getOrDefault(false)
    }?.name
}
