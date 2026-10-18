package com.juhyun.oddcamera.render

import android.graphics.Bitmap
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
import com.juhyun.oddcamera.record.VideoEncoder
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

/** Everything a capture needs to reproduce what the preview showed at the shutter moment. */
data class CaptureSnapshot(val filter: Filter, val params: ParamValues, val analysis: FrameAnalysis,
                           val mirror: Boolean, val rotationDeg: Int)

/**
 * Dedicated GL thread: camera OES texture -> upright/cropped/(mirrored) RGBA frame FBO -> analysis readback
 * -> active filter into the filtered FBO (frame size) -> display (aspect-fit) -> encoder while recording.
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
    private var analyzedStream: CameraStream? = null
    private var previous: FrameAnalysis? = null
    private lateinit var oesProgram: GlProgram
    private lateinit var blitProgram: GlProgram
    private var photoRenderer: PhotoRenderer? = null
    private var rotator: FrameRotator? = null

    /** Encoder target; rotation frozen at record start. rotFbo/outFbo are allocated when a frame needs them. */
    private class Recording(val encoder: VideoEncoder, val rot: Int, val surface: EGLSurface?, val w: Int, val h: Int) {
        var rotFbo: GlUtil.Fbo? = null
        var outFbo: GlUtil.Fbo? = null
        var readback: ByteBuffer? = null
        var ptsWarned = false
    }
    private var recording: Recording? = null
    /** True between startRecording and stopRecording (Task 11 freezes its governor on it). */
    @Volatile var isRecording = false
        private set

    private var display: EGLSurface? = null
    private var displayW = 0
    private var displayH = 0

    /** Stream whose frames are rendered (the newest request). */
    private var stream: CameraStream? = null
    /** Every stream not yet released, incl. replaced ones still awaiting CameraX's release callback. */
    private val liveStreams = ArrayList<CameraStream>()
    private var quitting = false
    private val governor = PerfGovernor()
    private var perfStartNs = 0L
    private var perfFrames = 0
    private var perfSumMs = 0f
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

    /**
     * Filters a still with the snapshot (see [PhotoRenderer.render]) on the GL thread, through the same renderer
     * instance the preview uses. Blocks the caller (not main) while the GL thread renders.
     */
    fun renderPhoto(src: Bitmap, snapshot: CaptureSnapshot): Bitmap = runSync {
        checkNotNull(egl) { "render thread shut down" }
        val photo = photoRenderer ?: PhotoRenderer().also { photoRenderer = it }
        photo.render(src, snapshot, rendererFor(snapshot.filter))
    } ?: error("render thread gone")

    /**
     * Starts feeding [encoder] (sized for rotationDeg, see encoderCandidates) with every rendered frame. Blocks until
     * the encoder EGL surface exists; throws if it can't be created (VideoEncoder.create then tries the next config).
     */
    fun startRecording(encoder: VideoEncoder, rotationDeg: Int) {
        runSync {
            val core = checkNotNull(egl) { "render thread shut down" }
            check(recording == null) { "already recording" }
            val c = encoder.config
            val surface = encoder.inputSurface()?.let { core.createWindowSurface(it) }
            recording = Recording(encoder, Math.floorMod(rotationDeg, 360), surface, c.width, c.height)
            isRecording = true
            governor.freeze(true)
        } ?: error("render thread gone")
    }

    /** Stops feeding the encoder and frees its EGL surface; the caller then calls encoder.stop(). Idempotent. */
    fun stopRecording() {
        runSync { releaseRecording() }
    }

    private fun releaseRecording() {
        val r = recording ?: return
        recording = null
        isRecording = false
        governor.freeze(false)
        idleSurface?.let { egl?.makeCurrent(it) }
        r.surface?.let { egl?.releaseSurface(it) }
        r.rotFbo?.release()
        r.outFbo?.release()
    }

    /** Blocks until the GL thread is ready. */
    fun startAndWait(): RenderThread {
        start()
        runSync { } // runs after onLooperPrepared
        return this
    }

    fun attachDisplay(surface: Surface, w: Int, h: Int) {
        runSync {
            val core = egl ?: return@runSync
            if (display == null) display = core.createWindowSurface(surface)
            displayW = w
            displayH = h
            drawDisplay()
        }
    }

    /** Blocks until the EGL surface is destroyed, so callers may return from surfaceDestroyed safely. */
    fun detachDisplay() {
        runSync { releaseDisplay() }
    }

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
            if (liveStreams.isEmpty()) finishShutdown() else handler.postDelayed(::finishShutdown, 1000) // fallback
        }
    }

    private fun finishShutdown() {
        if (egl == null) return
        releaseRecording()
        liveStreams.toList().forEach(::releaseStream)
        frame?.release()
        frame = null
        filtered?.release()
        filtered = null
        renderers.values.forEach { it.release() }
        renderers.clear()
        photoRenderer?.release()
        photoRenderer = null
        rotator?.release()
        rotator = null
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
        liveStreams += s
        st.setOnFrameAvailableListener({ if (stream === s) timedFrame(s) }, handler)
        request.setTransformationInfoListener({ handler.post(it) }) { info ->
            s.crop = info.cropRect
            s.rotationDeg = info.rotationDegrees
        }
        request.provideSurface(s.surface, { handler.post(it) }) {
            // Camera is done with this surface.
            if (egl != null) releaseStream(s)
            if (quitting && liveStreams.isEmpty()) finishShutdown()
        }
    }

    private fun releaseStream(s: CameraStream) {
        if (s.released) return
        s.released = true
        if (stream === s) stream = null
        liveStreams -= s
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

    /** Frame time = onFrameAvailable entry to the last swap's return (CPU wall time), fed to the governor + 1 Hz log. */
    private fun timedFrame(s: CameraStream) {
        val t0 = System.nanoTime()
        onFrame(s)
        val t1 = System.nanoTime()
        val ms = (t1 - t0) / 1e6f
        governor.onFrame(ms)
        perfFrames++
        perfSumMs += ms
        if (perfStartNs == 0L) perfStartNs = t0
        if (t1 - perfStartNs >= 1_000_000_000L) {
            Log.i(PERF_TAG, "fps=%.1f avgMs=%.1f tier=%d%s".format(perfFrames * 1e9f / (t1 - perfStartNs),
                perfSumMs / perfFrames, governor.index, if (isRecording) " rec" else ""))
            perfStartNs = t1; perfFrames = 0; perfSumMs = 0f
        }
    }

    private fun onFrame(s: CameraStream) {
        if (egl == null) return
        val mirror = mirror // read once: the OES pass and the capture snapshot must agree
        s.st.updateTexImage()
        s.st.getTransformMatrix(stMatrix)

        // Upright size = crop rotated by the sensor->natural rotation (portrait-locked activity).
        val swap = s.rotationDeg % 180 != 0
        val cw = if (swap) s.crop.height() else s.crop.width()
        val ch = if (swap) s.crop.width() else s.crop.height()
        if (cw <= 0 || ch <= 0) return
        val tier = PerfGovernor.DEFAULT_TIERS[governor.index]
        val fw = scaled(cw, tier.processScale)
        val fh = scaled(ch, tier.processScale)
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

        if (s !== analyzedStream) { // lens / mode switch: tracking and masks from the old stream don't apply
            analyzedStream = s
            previous = null
        }
        applyFilter(f, mirror, tier.analysisWidth)
        drawDisplay()
        recording?.let { r ->
            try {
                drawEncoder(r, f, s.st.timestamp)
            } catch (t: Throwable) { // a failing encoder must not take the preview down
                Log.e(TAG, "encoder frame failed; no longer feeding the encoder", t)
                releaseRecording()
                r.encoder.fail(t) // -> its onError; stop() aborts the row instead of saving a video that froze partway
            }
        }
    }

    /**
     * The filtered frame at encoder size in output orientation. Rotation 0: the preview's filtered FBO as is.
     * Otherwise like a photo: rotate the source frame and the analysis, then FilterPass at encoder size.
     */
    private fun drawEncoder(r: Recording, f: GlUtil.Fbo, cameraTsNs: Long) {
        val core = egl ?: return
        val snap = lastRendered ?: return
        var tex = filtered?.tex ?: return
        val pts = r.encoder.framePtsNs(cameraTsNs)
        if (pts < 0) return
        var srcW = f.width
        var srcH = f.height
        if (r.rot != 0) {
            val rotFbo = r.rotFbo ?: GlUtil.Fbo(r.w, r.h).also { r.rotFbo = it }
            val out = r.outFbo ?: GlUtil.Fbo(r.w, r.h).also { r.outFbo = it }
            (rotator ?: FrameRotator().also { rotator = it }).draw(f.tex, srcTopFirst = false, r.rot, mirror = false, rotFbo)
            FilterPass.render(rendererFor(snap.filter), rotFbo.tex, out.id, r.w, r.h, snap.params,
                FrameRotator.rotate(snap.analysis, r.rot))
            tex = out.tex
            srcW = r.w
            srcH = r.h
        }
        val surface = r.surface
        if (surface != null) {
            core.makeCurrent(surface)
            blit(tex, srcW, srcH, 0, r.w, r.h)
            if (!core.setPresentationTime(surface, pts) && !r.ptsWarned) {
                r.ptsWarned = true
                Log.w(TAG, "eglPresentationTimeANDROID failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
            check(core.swap(surface)) { "encoder eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        } else {
            // Buffer fallback: encoder-size FBO -> glReadPixels -> CPU YUV conversion inside the encoder.
            val out = r.outFbo ?: GlUtil.Fbo(r.w, r.h).also { r.outFbo = it }
            if (r.rot == 0) blit(tex, srcW, srcH, out.id, r.w, r.h)
            val buf = r.readback ?: ByteBuffer.allocateDirect(r.w * r.h * 4).also { r.readback = it }
            buf.clear()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, out.id)
            GLES30.glReadPixels(0, 0, r.w, r.h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            r.encoder.submitFrame(buf, pts)
        }
        GlUtil.checkGlError("encoder frame")
    }

    /** Draws tex (srcW×srcH) aspect-fit (black bars) into framebuffer fbo (0 = current surface) of size w×h. */
    private fun blit(tex: Int, srcW: Int, srcH: Int, fbo: Int, w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val v = GlUtil.aspectFit(srcW, srcH, w, h)
        GLES30.glViewport(v[0], v[1], v[2], v[3])
        blitProgram.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GlUtil.drawQuad(blitProgram)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** frame -> analysis -> active filter -> filtered FBO; publishes the analysis for capture. */
    private fun applyFilter(f: GlUtil.Fbo, mirror: Boolean, analysisWidth: Int) {
        val sel = selection
        if (sel.filter !== analyzedFilter) {
            analyzedFilter = sel.filter
            previous = null
        }
        val params = sel.params.snapshot()
        val luma = analysisReader.read(f.tex, f.width, f.height, analysisWidth,
            if (sel.filter.needsRgba(params)) SEG_WIDTH else 0)
        val analysis = sel.filter.analyze(luma, params, previous)
        previous = analysis

        val out = filtered?.takeIf { it.width == f.width && it.height == f.height } ?: run {
            filtered?.release()
            GlUtil.Fbo(f.width, f.height).also { filtered = it }
        }
        val renderer = rendererFor(sel.filter)
        FilterPass.render(renderer, f.tex, out.id, out.width, out.height, params, analysis)
        GlUtil.checkGlError("filter ${sel.filter.id}")

        latestAnalysis = analysis
        lastRendered = CaptureSnapshot(sel.filter, params, analysis, mirror, outputRotationDeg)
    }

    private fun rendererFor(filter: Filter) = renderers.getOrPut(filter) { filter.createRenderer().apply { init() } }

    private fun drawDisplay() {
        val core = egl ?: return
        val d = display ?: return
        core.makeCurrent(d)
        val f = filtered
        if (f != null) blit(f.tex, f.width, f.height, 0, displayW, displayH) else {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, displayW, displayH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GlUtil.checkGlError("display blit")
        if (!core.swap(d)) Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    /** Runs block on the GL thread and returns its result; null if the thread is gone. */
    private fun <T> runSync(block: () -> T): T? {
        val h = Handler(looper ?: return null) // getLooper() blocks until prepared; null once the thread died
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        val posted = h.post {
            try { result = block() } catch (t: Throwable) { error = t } finally { latch.countDown() }
        }
        if (!posted) return null
        latch.await()
        error?.let { throw it }
        return result
    }

    companion object {
        private const val TAG = "RenderThread"
        private const val PERF_TAG = "OddPerf"
        /** Even, >= 2. */
        private fun scaled(v: Int, scale: Float) = maxOf(2, Math.round(v * scale / 2f) * 2)
        /** Full analysis width (ruling R7: ≤160); the governor lowers the preview's. Photo analysis always uses this. */
        const val ANALYSIS_WIDTH = 160
        /** RGBA readback width for filters that need it (person segmentation); fixed across perf tiers. */
        const val SEG_WIDTH = 256

        private const val VS = """#version 300 es
in vec2 aPos;
uniform float uFlipX;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos.x * uFlipX, aPos.y, 0.0, 1.0);
}"""

        private const val OES_FS = """#version 300 es
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
