package com.juhyun.oddcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Box
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.juhyun.oddcamera.camera.CameraController
import com.juhyun.oddcamera.capture.MediaSaver
import com.juhyun.oddcamera.capture.PhotoCapturer
import com.juhyun.oddcamera.record.VideoEncoder
import com.juhyun.oddcamera.render.RenderThread
import com.juhyun.oddcamera.ui.CameraScreen
import com.juhyun.oddcamera.ui.CameraViewModel
import com.juhyun.oddcamera.ui.CaptureMode
import com.juhyun.oddcamera.ui.PermissionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var renderThread: RenderThread
    private lateinit var camera: CameraController
    private lateinit var orientationListener: OrientationEventListener
    private var granted by mutableStateOf(false)
    internal val vm: CameraViewModel by viewModels()
    /** Non-null while recording. */
    private var encoder: VideoEncoder? = null
    private var micAsked = false

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    /** Asked once per activity on the first video record; the shutter then proceeds whatever the answer. */
    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onShutter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderThread = RenderThread().startAndWait()
        camera = CameraController(this, this, renderThread)
        camera.onRebind = ::stopRecording // lens / mode switch
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) renderThread.outputRotationDeg = (orientation + 45) / 90 * 90 % 360
            }
        }
        granted = hasCameraPermission()
        if (!granted && savedInstanceState == null) requestPermission.launch(Manifest.permission.CAMERA)

        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PermissionGate(
                    granted = granted,
                    onRequest = { requestPermission.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)))
                    },
                ) {
                    CameraScreen(
                        vm, camera, renderThread,
                        onShutter = ::onShutter,
                    ) { AndroidView(::createPreviewView, Modifier.fillMaxSize()) }
                }
            }
        }
    }

    /**
     * Shutter (after the timer): freeze what the preview shows now, then capture. Never throws. Main thread.
     * Ignored while a capture is in flight: a queued still would be taken long after its snapshot.
     */
    private fun capturePhoto() {
        if (vm.capturing.value) return
        val snapshot = renderThread.snapshotForCapture()
        val ic = camera.imageCapture
        if (snapshot == null || ic == null) return photoFailed(null)
        vm.capturing.value = true
        lifecycleScope.launch {
            try {
                vm.lastCapture.value = PhotoCapturer(renderThread, ic, MediaSaver(contentResolver)).capture(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) { // incl. OutOfMemoryError: a failed photo must never take the camera down
                photoFailed(t)
            } finally {
                vm.capturing.value = false
            }
        }
    }

    private fun onShutter() {
        if (vm.mode.value == CaptureMode.VIDEO && encoder == null && !micAsked && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            micAsked = true
            return requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        vm.shutter(::capturePhoto, ::toggleRecording)
    }

    /** Starts recording, or stops and finalizes it. Main thread; also the test hook. */
    internal fun toggleRecording() = if (encoder != null) stopRecording() else startRecording()

    private fun startRecording() {
        val withAudio = hasPermission(Manifest.permission.RECORD_AUDIO)
        if (!withAudio) Toast.makeText(this, "마이크 권한 없음: 소리 없이 녹화", Toast.LENGTH_SHORT).show()
        val rot = renderThread.outputRotationDeg // frozen for this recording
        // ponytail: MediaStore insert + codec setup (~100 ms) run on the main thread once per start; move to IO if it janks.
        val pending = try { MediaSaver(contentResolver).beginVideo() } catch (t: Throwable) { return recordFailed(applicationContext, t) }
        var enc: VideoEncoder? = null
        try {
            enc = VideoEncoder.create(rot, pending, withAudio) { renderThread.startRecording(it, rot) }
            enc.start()
        } catch (t: Throwable) {
            renderThread.stopRecording()
            enc?.release()
            pending.abort()
            return recordFailed(applicationContext, t)
        }
        encoder = enc
        vm.recordingSince.value = SystemClock.elapsedRealtime()
    }

    /** Stops feeding frames now; the file finalizes in the background, surviving this activity. Idempotent. */
    private fun stopRecording() {
        val enc = encoder ?: return
        encoder = null
        vm.recordingSince.value = null
        renderThread.stopRecording()
        val app = applicationContext
        val vm = vm
        finalizeScope.launch {
            try {
                vm.lastCapture.value = enc.stop()
            } catch (t: Throwable) {
                recordFailed(app, t)
            }
        }
    }

    private fun photoFailed(t: Throwable?) {
        Log.e(TAG, "photo capture failed", t)
        Toast.makeText(this, "사진 저장 실패", Toast.LENGTH_SHORT).show()
    }

    private fun createPreviewView(context: android.content.Context) = SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) =
                renderThread.attachDisplay(holder.surface, w, h)
            override fun surfaceDestroyed(holder: SurfaceHolder) = renderThread.detachDisplay()
        })
    }

    override fun onStart() {
        super.onStart()
        granted = hasCameraPermission() // may have changed in system settings
        orientationListener.enable()
    }

    override fun onStop() {
        vm.cancelCountdown() // a video countdown must not start recording in the background
        stopRecording()
        orientationListener.disable()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderThread.shutdown()
    }

    private fun hasCameraPermission() = hasPermission(Manifest.permission.CAMERA)

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "MainActivity"
        /** Finalizing a video must outlive the activity (onStop/destroy mid-recording). */
        val finalizeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun recordFailed(context: android.content.Context, t: Throwable) {
            Log.e(TAG, "video recording failed", t)
            Toast.makeText(context, "동영상 저장 실패", Toast.LENGTH_SHORT).show()
        }
    }
}
