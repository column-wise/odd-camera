package com.juhyun.oddcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import com.juhyun.oddcamera.render.RenderThread
import com.juhyun.oddcamera.ui.CameraScreen
import com.juhyun.oddcamera.ui.CameraViewModel
import com.juhyun.oddcamera.ui.PermissionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var renderThread: RenderThread
    private lateinit var camera: CameraController
    private lateinit var orientationListener: OrientationEventListener
    private var granted by mutableStateOf(false)
    private val vm: CameraViewModel by viewModels()

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderThread = RenderThread().startAndWait()
        camera = CameraController(this, this, renderThread)
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
                        onCapturePhoto = ::capturePhoto,
                        onToggleRecording = { Log.i(TAG, "toggle recording (Task 10)") },
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
        orientationListener.disable()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderThread.shutdown()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "MainActivity"
    }
}
