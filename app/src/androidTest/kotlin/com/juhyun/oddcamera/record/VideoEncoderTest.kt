package com.juhyun.oddcamera.record

import android.Manifest
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
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
