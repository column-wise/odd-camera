package com.juhyun.oddcamera.record

import android.Manifest
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.opengl.GLES30
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.juhyun.oddcamera.capture.MediaSaver
import com.juhyun.oddcamera.gl.EglCore
import com.juhyun.oddcamera.gl.GlUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Synthetic GL source -> VideoEncoder -> MP4 in MediaStore; checks tracks, size, duration and frame content. */
@RunWith(AndroidJUnit4::class)
class VideoEncoderTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val supported = encoderCandidates(0) { avcEncoderFor(it) != null }

    /** Ruling R6: the mic-denied path is withAudio = false -> valid video, no audio track. */
    @Test fun surfaceInputWithoutAudioHasNoAudioTrack() = check(supported.first { it.surfaceInput }, withAudio = false)

    @Test fun surfaceInputWithAudioHasAudioTrack() = check(supported.first { it.surfaceInput }, withAudio = true)

    @Test fun bufferInputFallbackEncodesFrames() = check(supported.first { !it.surfaceInput }, withAudio = false)

    /** A failure reported mid-recording (e.g. the render thread's encoder swap failing) aborts the row; no frozen file. */
    @Test fun failedRecordingAbortsRow() {
        val pending = MediaSaver(resolver).beginVideo()
        val enc = VideoEncoder(supported.first { it.surfaceInput }, pending, withAudio = false)
        val egl = EglCore()
        val surf = egl.createWindowSurface(enc.inputSurface()!!)
        egl.makeCurrent(surf)
        enc.start()
        repeat(10) { i ->
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            egl.setPresentationTime(surf, enc.framePtsNs((i + 1) * 33_333_333L))
            egl.swap(surf)
            Thread.sleep(33)
        }
        enc.fail(IllegalStateException("encoder eglSwapBuffers failed"))
        val thrown = runCatching { runBlocking { enc.stop() } }.exceptionOrNull()
        egl.releaseSurface(surf)
        egl.release()
        assertEquals("encoder eglSwapBuffers failed", thrown?.message)
        val args = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
        resolver.query(pending.uri, null, args, null)!!.use { assertEquals(0, it.count) }
    }

    /**
     * A muxer write failing mid-recording: the encoder reports it via onError, keeps draining so the render thread's
     * swaps into its input surface never block (preview freeze -> ANR), and stop() returns promptly, aborting the row.
     */
    @Test fun muxerFailureKeepsDrainingAndReportsError() {
        val pending = MediaSaver(resolver).beginVideo()
        val enc = VideoEncoder(supported.first { it.surfaceInput }, pending, withAudio = false)
        val reported = CountDownLatch(1)
        enc.onError = { reported.countDown() }
        enc.failWritesForTest = true
        var swapped = 0
        val feeder = thread {
            val egl = EglCore()
            val surf = egl.createWindowSurface(enc.inputSurface()!!)
            egl.makeCurrent(surf)
            enc.start()
            repeat(90) { i -> // ~3 s at 30 fps: far more than the codec's output buffers
                GLES30.glClearColor(i / 90f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                egl.setPresentationTime(surf, enc.framePtsNs((i + 1) * 33_333_333L))
                egl.swap(surf)
                swapped++
                Thread.sleep(33)
            }
            egl.releaseSurface(surf)
            egl.release()
        }
        feeder.join(15_000)
        assertTrue("encoder input blocked after $swapped frames", !feeder.isAlive)
        assertTrue("no error reported", reported.await(1, TimeUnit.SECONDS))
        val t0 = SystemClock.elapsedRealtime()
        val thrown = runCatching { runBlocking { enc.stop() } }.exceptionOrNull()
        assertTrue("stop took ${SystemClock.elapsedRealtime() - t0} ms", SystemClock.elapsedRealtime() - t0 < 2000)
        assertEquals("injected muxer write failure", thrown?.message)
        val args = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
        resolver.query(pending.uri, null, args, null)!!.use { assertEquals(0, it.count) }
    }

    private fun check(config: EncoderConfig, withAudio: Boolean) {
        val uri = record(config, withAudio)
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null)!!.use { c ->
                c.moveToFirst(); assertEquals(0, c.getInt(0))
            }
            val mimes = MediaExtractor().run {
                setDataSource(context, uri, null)
                val m = (0 until trackCount).map { getTrackFormat(it).getString(MediaFormat.KEY_MIME)!! }
                release()
                m
            }
            assertEquals(listOf("video/avc"), mimes.filter { it.startsWith("video/") })
            assertEquals(if (withAudio) listOf("audio/mp4a-latm") else emptyList(), mimes.filter { it.startsWith("audio/") })
            val r = MediaMetadataRetriever().apply { setDataSource(context, uri) }
            try {
                assertEquals(config.width, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt())
                assertEquals(config.height, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt())
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("duration $ms", ms >= 1000)
                // Top half red, bottom half blue: catches a vertical flip or a broken YUV conversion.
                val f = r.getFrameAtTime(500_000)!!
                val top = f.getPixel(f.width / 2, f.height / 4)
                val bottom = f.getPixel(f.width / 2, f.height * 3 / 4)
                assertTrue("top " + Integer.toHexString(top), Color.red(top) > 180 && Color.blue(top) < 80)
                assertTrue("bottom " + Integer.toHexString(bottom), Color.blue(bottom) > 180 && Color.red(bottom) < 80)
            } finally {
                r.release()
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun record(config: EncoderConfig, withAudio: Boolean): Uri {
        val enc = VideoEncoder(config, MediaSaver(resolver).beginVideo(), withAudio)
        assertEquals(withAudio, enc.hasAudio)
        val egl = EglCore()
        val w = config.width
        val h = config.height
        val surf = enc.inputSurface()?.let { egl.createWindowSurface(it) } ?: egl.createOffscreenSurface(1, 1)
        egl.makeCurrent(surf)
        val fbo = if (config.surfaceInput) null else GlUtil.Fbo(w, h)
        val buf = ByteBuffer.allocateDirect(w * h * 4)
        enc.start()
        repeat(45) { i ->
            val pts = enc.framePtsNs((i + 1) * 33_333_333L)
            assertTrue(pts >= 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo?.id ?: 0)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(0f, 0f, 1f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(0, h / 2, w, h - h / 2) // GL y is up: the top half
            GLES30.glClearColor(1f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            if (fbo == null) {
                assertTrue(egl.setPresentationTime(surf, pts))
                assertTrue(egl.swap(surf))
            } else {
                buf.clear()
                GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                enc.submitFrame(buf, pts)
            }
            GlUtil.checkGlError("frame $i")
            Thread.sleep(33)
        }
        val uri = runBlocking { enc.stop() }
        fbo?.release()
        egl.releaseSurface(surf)
        egl.release()
        return uri
    }
}
