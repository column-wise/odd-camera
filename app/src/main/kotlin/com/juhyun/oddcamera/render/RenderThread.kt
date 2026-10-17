package com.juhyun.oddcamera.render

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import com.juhyun.oddcamera.gl.EglCore
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil
import java.util.concurrent.CountDownLatch

/**
 * Dedicated GL thread: camera OES texture -> upright/cropped/(mirrored) RGBA frame FBO -> display (aspect-fit).
 * Task 5 inserts analysis + filter between the frame FBO and the display blit; Task 10 adds an encoder surface.
 */
class RenderThread : HandlerThread("RenderThread") {
    /** Output mirrored horizontally (selfie style). Applied when drawing OES -> frame FBO. */
    @Volatile var mirror = false
    /** Device orientation snapped to 0/90/180/270; consumed at shutter / record start (Task 9/10). */
    @Volatile var outputRotationDeg = 0

    private lateinit var handler: Handler
    private var egl: EglCore? = null
    private lateinit var oesProgram: GlProgram
    private lateinit var blitProgram: GlProgram

    private var display: EGLSurface? = null
    private var displayW = 0
    private var displayH = 0

    private var stream: CameraStream? = null
    private var quitting = false
    private var frame: GlUtil.Fbo? = null

    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)

    /** One SurfaceRequest's resources. Buffer size and crop are in sensor-buffer coordinates. */
    private class CameraStream(val tex: Int, val st: SurfaceTexture, val surface: Surface, val bufW: Int, val bufH: Int) {
        var crop = Rect(0, 0, bufW, bufH)
        var rotationDeg = 0
        var released = false
    }

    override fun onLooperPrepared() {
        handler = Handler(looper)
        val core = EglCore()
        core.makeCurrent(EGL14.EGL_NO_SURFACE)
        egl = core
        oesProgram = GlProgram(VS, OES_FS)
        blitProgram = GlProgram(VS, BLIT_FS)
    }

    /** Blocks until the GL thread is ready. */
    fun startAndWait(): RenderThread {
        start()
        runSync { } // runs after onLooperPrepared
        return this
    }

    fun attachDisplay(surface: Surface, w: Int, h: Int) = runSync {
        val core = egl ?: return@runSync
        if (display == null) display = core.createWindowSurface(surface)
        displayW = w
        displayH = h
        drawDisplay()
    }

    /** Blocks until the EGL surface is destroyed, so callers may return from surfaceDestroyed safely. */
    fun detachDisplay() = runSync { releaseDisplay() }

    fun cameraSurfaceProvider(): Preview.SurfaceProvider = Preview.SurfaceProvider { request ->
        handler.post { onSurfaceRequested(request) }
    }

    /**
     * Releases GL/EGL resources and quits the thread. The camera stream is released only once CameraX reports
     * it is done with the surface (it unbinds on ON_DESTROY), so the producer never hits an abandoned queue.
     */
    fun shutdown() {
        handler.post {
            quitting = true
            releaseDisplay()
            if (stream == null) finishShutdown() else handler.postDelayed(::finishShutdown, 1000) // fallback
        }
    }

    private fun finishShutdown() {
        if (egl == null) return
        stream?.let { releaseStream(it) }
        frame?.release()
        frame = null
        oesProgram.release()
        blitProgram.release()
        egl?.release()
        egl = null
        quitSafely()
    }

    private fun onSurfaceRequested(request: SurfaceRequest) {
        if (egl == null || quitting) {
            request.willNotProvideSurface()
            return
        }
        val size = request.resolution
        val tex = GlUtil.createOesTexture()
        val st = SurfaceTexture(tex).apply { setDefaultBufferSize(size.width, size.height) }
        val s = CameraStream(tex, st, Surface(st), size.width, size.height)
        stream = s // a previous stream stays alive until its own result callback below
        st.setOnFrameAvailableListener({ if (stream === s) onFrame(s) }, handler)
        request.setTransformationInfoListener({ handler.post(it) }) { info ->
            s.crop = info.cropRect
            s.rotationDeg = info.rotationDegrees
        }
        request.provideSurface(s.surface, { handler.post(it) }) {
            // Camera is done with this surface.
            if (egl != null) releaseStream(s)
            if (quitting && stream == null) finishShutdown()
        }
    }

    private fun releaseStream(s: CameraStream) {
        if (s.released) return
        s.released = true
        if (stream === s) stream = null
        s.st.setOnFrameAvailableListener(null)
        s.surface.release()
        s.st.release()
        GLES30.glDeleteTextures(1, intArrayOf(s.tex), 0)
    }

    private fun releaseDisplay() {
        val d = display ?: return
        egl?.makeCurrent(EGL14.EGL_NO_SURFACE)
        egl?.releaseSurface(d)
        display = null
    }

    private fun onFrame(s: CameraStream) {
        if (egl == null) return
        s.st.updateTexImage()
        s.st.getTransformMatrix(stMatrix)

        // Upright size = crop rotated by the sensor->natural rotation (portrait-locked activity).
        val swap = s.rotationDeg % 180 != 0
        val fw = if (swap) s.crop.height() else s.crop.width()
        val fh = if (swap) s.crop.width() else s.crop.height()
        if (fw <= 0 || fh <= 0) return
        val f = frame?.takeIf { it.width == fw && it.height == fh } ?: run {
            frame?.release()
            GlUtil.Fbo(fw, fh).also { frame = it }
        }

        // Texture coords: crop (in buffer space) applied after the SurfaceTexture transform.
        Matrix.setIdentityM(cropMatrix, 0)
        Matrix.translateM(cropMatrix, 0, s.crop.left.toFloat() / s.bufW, s.crop.top.toFloat() / s.bufH, 0f)
        Matrix.scaleM(cropMatrix, 0, s.crop.width().toFloat() / s.bufW, s.crop.height().toFloat() / s.bufH, 1f)
        Matrix.multiplyMM(texMatrix, 0, cropMatrix, 0, stMatrix, 0)
        // A front camera stream may already arrive mirrored (producer flip): det > 0 since the ST matrix
        // otherwise always contains a vertical flip. Flip only when that differs from what we want.
        val sourceMirrored = stMatrix[0] * stMatrix[5] - stMatrix[1] * stMatrix[4] > 0
        val flipX = if (mirror != sourceMirrored) -1f else 1f

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f.id)
        GLES30.glViewport(0, 0, fw, fh)
        oesProgram.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.tex)
        GLES30.glUniformMatrix4fv(oesProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform1f(oesProgram.uniform("uFlipX"), flipX)
        drawQuad(oesProgram)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlUtil.checkGlError("oes->frame")

        // Task 5: analysis + filter pass go here (frame FBO -> filtered target).
        drawDisplay()
    }

    private fun drawDisplay() {
        val core = egl ?: return
        val d = display ?: return
        core.makeCurrent(d)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, displayW, displayH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        frame?.let { f ->
            val r = GlUtil.aspectFit(f.width, f.height, displayW, displayH)
            GLES30.glViewport(r[0], r[1], r[2], r[3])
            blitProgram.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, f.tex)
            GLES30.glUniform1f(blitProgram.uniform("uFlipX"), 1f)
            drawQuad(blitProgram)
        }
        GlUtil.checkGlError("display blit")
        if (!core.swap(d)) Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    private fun drawQuad(p: GlProgram) {
        val pos = p.attrib("aPos")
        GLES30.glEnableVertexAttribArray(pos)
        GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, 0, GlUtil.QUAD)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(pos)
    }

    private fun runSync(block: () -> Unit) {
        val h = Handler(looper ?: return) // getLooper() blocks until prepared; null once the thread died
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        val posted = h.post {
            try { block() } catch (t: Throwable) { error = t } finally { latch.countDown() }
        }
        if (!posted) return
        latch.await()
        error?.let { throw it }
    }

    private companion object {
        const val TAG = "RenderThread"

        const val VS = """#version 300 es
in vec2 aPos;
uniform float uFlipX;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos.x * uFlipX, aPos.y, 0.0, 1.0);
}"""

        // uTexMatrix only used by the OES program.
        const val OES_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
in vec2 vUv;
out vec4 outColor;
void main() {
    outColor = vec4(texture(uTex, (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy).rgb, 1.0);
}"""

        const val BLIT_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 outColor;
void main() { outColor = texture(uTex, vUv); }"""
    }
}
