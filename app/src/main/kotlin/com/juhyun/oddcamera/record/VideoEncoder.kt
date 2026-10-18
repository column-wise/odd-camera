package com.juhyun.oddcamera.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.juhyun.oddcamera.capture.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * H.264 (surface or RGBA-buffer input) + optional AAC from the mic -> MP4 in [pending]. Video and audio timestamps
 * share one base: nanoseconds since [start]. Construction configures the codecs (throws if this config can't run);
 * [start] begins encoding; [stop] finalizes and commits (or aborts) the pending row.
 */
class VideoEncoder(val config: EncoderConfig, private val pending: MediaSaver.PendingVideo, withAudio: Boolean) {
    private val video: MediaCodec
    private val surface: Surface?
    private var audioRecord: AudioRecord? = null
    private var audio: MediaCodec? = null

    private val muxLock = Any()
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var expectAudio = false
    private var muxerStarted = false
    /** Samples produced before every track's format was known; written once the muxer starts. */
    private val early = ArrayList<Triple<Boolean, ByteBuffer, MediaCodec.BufferInfo>>()

    @Volatile private var started = false
    @Volatile private var stopping = false
    /** Set after stop() gave up on (or finished with) the drain threads: they must exit. */
    @Volatile private var abandoned = false
    @Volatile private var error: Throwable? = null
    @Volatile private var videoEos = false
    private var startNs = 0L
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    // Render thread only (framePtsNs).
    private var firstFrameTs = -1L
    private var frameOffsetNs = 0L
    private var lastPts = -1L

    // Buffer-input mode.
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val rgbaCopy: ByteBuffer? = if (config.surfaceInput) null else ByteBuffer.allocateDirect(config.width * config.height * 4)

    // Audio thread only.
    private var audioInputDone = false
    private var audioStartUs = 0L
    private var samples = 0L

    val hasAudio get() = audio != null

    init {
        val name = checkNotNull(avcEncoderFor(config)) { "no H.264 encoder for $config" }
        video = MediaCodec.createByCodecName(name)
        try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, if (config.surfaceInput) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            video.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = if (config.surfaceInput) video.createInputSurface() else null
        } catch (t: Throwable) {
            video.release()
            throw t
        }
        Log.i(TAG, "video $name $config")
        if (withAudio) try { setupAudio() } catch (t: Throwable) {
            // A broken mic must not cost the video: record silently.
            Log.w(TAG, "audio unavailable, recording without it", t)
            releaseAudio()
        }
        expectAudio = audio != null
    }

    @SuppressLint("MissingPermission") // the caller passes withAudio only with RECORD_AUDIO granted
    private fun setupAudio() {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, CHUNK * 4))
        audioRecord = rec
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 4)
        }
        audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { it.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
    }

    /** Encoder input surface (surface mode), or null in buffer mode. */
    fun inputSurface(): Surface? = surface

    fun start() {
        muxer = MediaMuxer(pending.fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        video.start()
        startNs = System.nanoTime()
        audio?.let { a ->
            a.start()
            audioRecord!!.startRecording()
            audioStartUs = (System.nanoTime() - startNs) / 1000
            audioThread = thread(name = "AudioEncoder") { drainLoop(a, isVideo = false, feed = ::feedAudio) }
        }
        started = true
        videoThread = thread(name = "VideoEncoder") { drainLoop(video, isVideo = true) }
    }

    /**
     * Camera frame timestamp -> encoder pts in ns (base of [start]), or -1 to drop the frame (not started, stopping,
     * or not after the previous one). Render thread.
     */
    fun framePtsNs(cameraTsNs: Long): Long {
        if (!started || stopping) return -1
        val now = System.nanoTime()
        val ts = if (cameraTsNs > 0) cameraTsNs else now
        if (firstFrameTs < 0) {
            firstFrameTs = ts
            frameOffsetNs = now - startNs // camera clock base differs per device: anchor its first frame to arrival
        }
        val pts = ts - firstFrameTs + frameOffsetNs
        if (pts <= lastPts) return -1
        lastPts = pts
        return pts
    }

    /**
     * Buffer mode: rgba is w×h RGBA, GL row order (row 0 = bottom). Copied, then converted to YUV 4:2:0 on a worker;
     * dropped while the previous frame is still converting.
     */
    fun submitFrame(rgba: ByteBuffer, ptsNs: Long) {
        val copy = checkNotNull(rgbaCopy) { "surface-input encoder" }
        if (stopping || !busy.compareAndSet(false, true)) return
        copy.clear()
        copy.put(rgba.duplicate().apply { rewind() })
        copy.flip()
        try {
            worker.execute {
                try { queueYuv(copy, ptsNs) } catch (t: Throwable) { fail(t) } finally { busy.set(false) }
            }
        } catch (t: Throwable) { busy.set(false) } // rejected: stopping
    }

    private fun queueYuv(rgba: ByteBuffer, ptsNs: Long) {
        val i = video.dequeueInputBuffer(20_000)
        if (i < 0) return // encoder busy: drop
        val img = checkNotNull(video.getInputImage(i)) { "no input image" }
        rgbaToYuv(rgba, config.width, config.height, img)
        video.queueInputBuffer(i, 0, config.width * config.height * 3 / 2, ptsNs / 1000, 0)
    }

    /**
     * Signals EOS, drains with a timeout, releases everything, then commits the row and returns its Uri; on failure
     * aborts the row and throws.
     */
    suspend fun stop(): Uri = withContext(Dispatchers.IO) {
        stopping = true
        try {
            if (surface != null) video.signalEndOfInputStream() else {
                worker.shutdown()
                worker.awaitTermination(1, TimeUnit.SECONDS)
                val i = video.dequeueInputBuffer(500_000)
                check(i >= 0) { "no input buffer for EOS" }
                video.queueInputBuffer(i, 0, 0, maxOf(0, lastPts) / 1000 + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (t: Throwable) { fail(t) }
        videoThread?.join(STOP_TIMEOUT_MS)
        audioThread?.join(STOP_TIMEOUT_MS)
        val stuck = videoThread?.isAlive == true || audioThread?.isAlive == true
        release()
        val failure = when {
            stuck -> TimeoutException("encoder did not reach end of stream")
            error != null -> error
            !videoEos -> IllegalStateException("video stream did not end")
            else -> null
        }
        val muxFailure = runCatching { synchronized(muxLock) { check(muxerStarted) { "no frames recorded" }; muxer!!.stop() } }.exceptionOrNull()
        runCatching { muxer?.release() }
        val f = failure ?: muxFailure
        if (f != null) {
            pending.abort()
            throw f
        }
        pending.commit()
        pending.uri
    }

    /** Frees codecs, mic and surface; never touches the pending row or a started muxer. Idempotent. */
    fun release() {
        stopping = true
        abandoned = true
        worker.shutdownNow()
        releaseAudio()
        runCatching { video.stop() }
        video.release()
        surface?.release()
        if (!started) runCatching { muxer?.release() }
    }

    private fun releaseAudio() {
        audioRecord?.let { runCatching { it.stop() }; it.release() }
        audio?.let { runCatching { it.stop() }; it.release() }
    }

    /** Marks the recording failed: stop() then aborts the row and throws. Any thread (e.g. the render thread's GL errors). */
    fun fail(t: Throwable) {
        Log.e(TAG, "encoder error", t)
        if (error == null) error = t
    }

    private fun feedAudio() {
        if (audioInputDone) return
        val codec = audio!!
        val i = codec.dequeueInputBuffer(10_000)
        if (i < 0) return
        val pts = audioStartUs + samples * 1_000_000L / SAMPLE_RATE
        if (stopping) {
            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            audioInputDone = true
            return
        }
        val buf = codec.getInputBuffer(i)!!
        val n = audioRecord!!.read(buf, minOf(buf.capacity(), CHUNK))
        check(n >= 0) { "AudioRecord.read: $n" }
        codec.queueInputBuffer(i, 0, n, pts, 0)
        samples += n / 2
    }

    /** Drains codec output into the muxer until end of stream; errors end the loop. */
    private fun drainLoop(codec: MediaCodec, isVideo: Boolean, feed: (() -> Unit)? = null) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!abandoned) {
                feed?.invoke()
                val i = codec.dequeueOutputBuffer(info, 10_000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) addTrack(isVideo, codec.outputFormat)
                if (i < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) write(isVideo, codec.getOutputBuffer(i)!!, info)
                codec.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    if (isVideo) videoEos = true
                    return
                }
            }
        } catch (t: Throwable) {
            if (abandoned) return
            if (isVideo) return fail(t)
            // Audio failure: keep the video, end the audio track here (or go video-only if it never started).
            Log.e(TAG, "audio failed; continuing without it", t)
            synchronized(muxLock) { if (audioTrack < 0) { expectAudio = false; maybeStartMuxer() } }
        }
    }

    private fun addTrack(isVideo: Boolean, format: MediaFormat) = synchronized(muxLock) {
        val m = muxer!!
        if (isVideo) videoTrack = m.addTrack(format) else audioTrack = m.addTrack(format)
        maybeStartMuxer()
    }

    private fun maybeStartMuxer() {
        if (muxerStarted || videoTrack < 0 || (expectAudio && audioTrack < 0)) return
        muxer!!.start()
        muxerStarted = true
        for ((v, b, info) in early) muxer!!.writeSampleData(if (v) videoTrack else audioTrack, b, info)
        early.clear()
    }

    private fun write(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(muxLock) {
        buf.position(info.offset).limit(info.offset + info.size)
        if (muxerStarted) return@synchronized muxer!!.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
        if (!isVideo && !expectAudio) return@synchronized
        val copy = ByteBuffer.allocateDirect(info.size).put(buf).apply { flip() }
        early += Triple(isVideo, copy, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
    }

    companion object {
        private const val TAG = "VideoEncoder"
        private const val SAMPLE_RATE = 44_100
        /** Bytes per AudioRecord read (1024 mono 16-bit samples ≈ 23 ms). */
        private const val CHUNK = 2048
        private const val STOP_TIMEOUT_MS = 3000L

        /**
         * Tries [encoderCandidates] (unsupported sizes skipped) in order. A candidate fails if configuring it throws or
         * `attach` (e.g. RenderThread.startRecording, which creates the EGL surface) throws. Caller then calls start().
         */
        fun create(rotationDeg: Int, pending: MediaSaver.PendingVideo, withAudio: Boolean, attach: (VideoEncoder) -> Unit): VideoEncoder {
            var last: Throwable? = null
            for (c in encoderCandidates(rotationDeg) { avcEncoderFor(it) != null }) {
                val enc = try { VideoEncoder(c, pending, withAudio) } catch (t: Throwable) {
                    Log.w(TAG, "candidate $c failed", t)
                    last = t
                    continue
                }
                try {
                    attach(enc)
                    return enc
                } catch (t: Throwable) {
                    Log.w(TAG, "candidate $c failed to attach", t)
                    last = t
                    enc.release()
                }
            }
            throw IllegalStateException("no usable video encoder", last)
        }

        /** BT.601 limited-range RGBA (row 0 = bottom) -> the codec's YUV 4:2:0 image (any plane layout, e.g. NV12/I420). */
        fun rgbaToYuv(rgba: ByteBuffer, w: Int, h: Int, img: Image) {
            val (yp, up, vp) = img.planes
            val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
            for (row in 0 until h) {
                val src = (h - 1 - row) * w * 4
                val yo = row * yp.rowStride
                val even = row and 1 == 0
                for (x in 0 until w) {
                    val o = src + x * 4
                    val r = rgba.get(o).toInt() and 255
                    val g = rgba.get(o + 1).toInt() and 255
                    val b = rgba.get(o + 2).toInt() and 255
                    yb.put(yo + x * yp.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                    if (even && x and 1 == 0) {
                        // ponytail: chroma from the top-left pixel of each 2×2 block, average if fine edges alias.
                        ub.put((row / 2) * up.rowStride + (x / 2) * up.pixelStride, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                        vb.put((row / 2) * vp.rowStride + (x / 2) * vp.pixelStride, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                    }
                }
            }
        }
    }
}
