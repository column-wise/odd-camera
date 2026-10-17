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
import com.juhyun.oddcamera.filter.Filter
import com.juhyun.oddcamera.filter.FilterRenderer
import com.juhyun.oddcamera.filter.FrameAnalysis
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.none.NoneFilter
import com.juhyun.oddcamera.gl.EglCore
import com.juhyun.oddcamera.gl.GlProgram
import com.juhyun.oddcamera.gl.GlUtil
import java.util.concurrent.CountDownLatch

/** Everything a capture needs to reproduce what the preview showed at the shutter moment. */
data class CaptureSnapshot(val filter: Filter, val params: ParamValues, val analysis: FrameAnalysis,
                           val mirror: Boolean, val rotationDeg: Int)

/**
 * Dedicated GL thread: camera OES texture -> upright/cropped/(mirrored) RGBA frame FBO -> analysis readback
 * -> active filter into the filtered FBO (frame size) -> display (aspect-fit). Task 10 adds an encoder surface.
 */
class RenderThread : HandlerThread("RenderThread") {
    /** Output mirrored horizontally (selfie style). Applied when drawing OES -> frame FBO. */
    @Volatile var mirror = false
    /** Device orientation snapped to 0/90/180/270; consumed at shutter / record start (Task 9/10). */
    @Volatile var outputRotationDeg = 0

    private class Selection(val filter: Filter, val params: ParamValues)
    /** Written by the UI thread (setFilter), read once per frame on the GL thread. */
    @Volatile private var selection = Selection(NoneFilter, ParamValues(NoneFilter.params))

    /** Analysis of the last rendered frame. */
    @Volatile var latestAnalysis: FrameAnalysis? = null
        private set
    /** Filter, params snapshot and analysis of the last rendered frame, published together. */
    @Volatile private var lastRendered: CaptureSnapshot? = null

    private lateinit var handler: Handler
    private var egl: EglCore? = null
    private var idleSurface: EGLSurface? = null
    private lateinit var analysisReader: AnalysisReader
    private val renderers = HashMap<Filter, FilterRenderer>()
    private var analyzedFilter: Filter? = null
    private var previous: FrameAnalysis? = null
    private lateinit var oesProgram: GlProgram
    private lateinit var blitProgram: GlProgram

    private var display: EGLSurface? = null
    private var displayW = 0
    private var displayH = 0

    private var stream: CameraStream? = null
    private var quitting = false
    private var frame: GlUtil.Fbo? = null
    private var filtered: GlUtil.Fbo? = null

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
        val idle = core.createOffscreenSurface(1, 1)
        core.makeCurrent(idle)
        idleSurface = idle
        egl = core
        oesProgram = GlProgram(VS, OES_FS)
        blitProgram = GlProgram(GlUtil.QUAD_VS, GlUtil.COPY_FS)
        analysisReader = AnalysisReader()
    }

    /** UI thread. `params` is the live instance; it is snapshotted once per frame on the GL thread. */
    fun setFilter(filter: Filter, params: ParamValues) {
        selection = Selection(filter, params)
    }

    /** Null until the first frame has been rendered. Thread-safe. */
    fun snapshotForCapture(): CaptureSnapshot? = lastRendered?.copy(rotationDeg = outputRotationDeg)

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
        filtered?.release()
        filtered = null
        renderers.values.forEach { it.release() }
        renderers.clear()
        analysisReader.release()
        oesProgram.release()
        blitProgram.release()
        idleSurface?.let { egl?.releaseSurface(it) }
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
        idleSurface?.let { egl?.makeCurrent(it) }
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
        GlUtil.drawQuad(oesProgram)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlUtil.checkGlError("oes->frame")

        applyFilter(f)
        drawDisplay()
    }

    /** frame -> analysis -> active filter -> filtered FBO; publishes the analysis for capture. */
    private fun applyFilter(f: GlUtil.Fbo) {
        val sel = selection
        if (sel.filter !== analyzedFilter) {
            analyzedFilter = sel.filter
            previous = null
        }
        val params = sel.params.snapshot()
        val luma = analysisReader.read(f.tex, f.width, f.height, ANALYSIS_WIDTH)
        val analysis = sel.filter.analyze(luma, params, previous)
        previous = analysis

        val out = filtered?.takeIf { it.width == f.width && it.height == f.height } ?: run {
            filtered?.release()
            GlUtil.Fbo(f.width, f.height).also { filtered = it }
        }
        val renderer = renderers.getOrPut(sel.filter) { sel.filter.createRenderer().apply { init() } }
        FilterPass.render(renderer, f.tex, out.id, out.width, out.height, params, analysis)
        GlUtil.checkGlError("filter ${sel.filter.id}")

        latestAnalysis = analysis
        lastRendered = CaptureSnapshot(sel.filter, params, analysis, mirror, outputRotationDeg)
    }

    private fun drawDisplay() {
        val core = egl ?: return
        val d = display ?: return
        core.makeCurrent(d)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, displayW, displayH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        filtered?.let { f ->
            val r = GlUtil.aspectFit(f.width, f.height, displayW, displayH)
            GLES30.glViewport(r[0], r[1], r[2], r[3])
            blitProgram.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, f.tex)
            GlUtil.drawQuad(blitProgram)
        }
        GlUtil.checkGlError("display blit")
        if (!core.swap(d)) Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
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
        /** Analysis readback width (ruling R7: ≤160). Task 11's governor may lower it. */
        const val ANALYSIS_WIDTH = 160

        const val VS = """#version 300 es
in vec2 aPos;
uniform float uFlipX;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos.x * uFlipX, aPos.y, 0.0, 1.0);
}"""

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
    }
}
