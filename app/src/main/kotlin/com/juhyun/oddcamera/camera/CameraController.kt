package com.juhyun.oddcamera.camera

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Display
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.juhyun.oddcamera.render.RenderThread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

enum class Aspect { PHOTO_4_3, VIDEO_16_9 }
enum class FlashMode { OFF, AUTO, ON }

/** Binds CameraX use cases to the lifecycle owner; preview frames go to the RenderThread. Main thread only. */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val renderThread: RenderThread,
) {
    /** Currently bound lens (CameraSelector.LENS_FACING_*) and aspect; changed only by a successful bind. */
    private var lens = CameraSelector.LENS_FACING_BACK
    private var aspect = Aspect.PHOTO_4_3
    private var camera: Camera? = null

    private val _zoomRange = MutableStateFlow(1f..1f)
    val zoomRange: StateFlow<ClosedFloatingPointRange<Float>> = _zoomRange
    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio

    var flashMode = FlashMode.OFF
        private set

    /** Bound only in photo mode (same UseCaseGroup/ViewPort as the preview); null in video mode. */
    var imageCapture: ImageCapture? = null
        private set

    /** Called before every (re)bind, i.e. lens and mode switches: a recording must stop and finalize first. */
    var onRebind: () -> Unit = {}

    /** User-facing bind failure messages (Korean). */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors

    fun setAspect(a: Aspect) = bind(lens, a)

    fun switchLens() = bind(
        if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
        aspect,
    )

    fun setZoomRatio(r: Float) {
        val c = camera ?: return
        val z = r.coerceIn(_zoomRange.value)
        _zoomRatio.value = z // optimistic, so pinch steps compound on the requested ratio
        c.cameraControl.setZoomRatio(z)
    }

    fun setFlash(mode: FlashMode) {
        flashMode = mode
        applyFlash()
    }

    private fun applyFlash() {
        val ic = imageCapture ?: return
        // A lens without a flash unit (most front cameras) stays OFF.
        ic.flashMode = if (camera?.cameraInfo?.hasFlashUnit() != true) ImageCapture.FLASH_MODE_OFF else when (flashMode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        }
    }

    fun setTorch(on: Boolean) { camera?.cameraControl?.enableTorch(on) }

    /** Normalized coords in the displayed (upright, mirrored-for-front) preview. */
    fun focusAt(xNorm: Float, yNorm: Float) {
        val c = camera ?: return
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        // ponytail: maps against the full camera frame, ignoring the viewport crop (none for 4:3 photo / 16:9 video
        // streams at their native aspects); account for the crop rect if a fallback resolution crops.
        val point = DisplayOrientedMeteringPointFactory(display, c.cameraInfo, 1f, 1f).createPoint(xNorm, yNorm)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        c.cameraControl.startFocusAndMetering(action)
    }

    private fun bind(lens: Int, aspect: Aspect) {
        onRebind()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            if (!provider.hasCamera(selector)) {
                _errors.tryEmit(if (lens == CameraSelector.LENS_FACING_FRONT) "전면 카메라를 사용할 수 없습니다" else "후면 카메라를 사용할 수 없습니다")
                return@addListener
            }
            try {
                bindNow(provider, lens, aspect)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                _errors.tryEmit("카메라를 열 수 없습니다")
                if (camera != null && (lens != this.lens || aspect != this.aspect)) {
                    // Keep the previous configuration running.
                    try { bindNow(provider, this.lens, this.aspect) } catch (e2: Exception) { Log.e(TAG, "rebind failed", e2) }
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindNow(provider: ProcessCameraProvider, lens: Int, aspect: Aspect) {
        val (ratio, strategy) = when (aspect) {
            // Portrait-locked activity: viewport is width:height in portrait.
            Aspect.PHOTO_4_3 -> Rational(3, 4) to AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            Aspect.VIDEO_16_9 -> Rational(9, 16) to AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        }
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(strategy).build())
            .setTargetRotation(Surface.ROTATION_0)
            // Video is 30 fps: a steady camera rate keeps frame spacing even (CameraX picks the closest range).
            .apply { if (aspect == Aspect.VIDEO_16_9) setTargetFrameRate(Range(30, 30)) }
            .build()
        preview.setSurfaceProvider(renderThread.cameraSurfaceProvider())
        // ~12 MP still (R14): capped so 50-200 MP sensors never feed the multi-bitmap photo pipeline.
        val capture = if (aspect == Aspect.PHOTO_4_3) ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(strategy)
                .setResolutionStrategy(ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build())
            .setTargetRotation(Surface.ROTATION_0)
            .build() else null
        // The shared ViewPort keeps preview crop == capture crop.
        val group = UseCaseGroup.Builder()
            .setViewPort(ViewPort.Builder(ratio, Surface.ROTATION_0).build())
            .addUseCase(preview)
            .apply { capture?.let(::addUseCase) }
            .build()
        camera?.cameraInfo?.zoomState?.removeObservers(lifecycleOwner)
        provider.unbindAll()
        imageCapture = null
        val c = provider.bindToLifecycle(lifecycleOwner, CameraSelector.Builder().requireLensFacing(lens).build(), group)
        renderThread.mirror = lens == CameraSelector.LENS_FACING_FRONT
        camera = c
        imageCapture = capture
        applyFlash()
        this.lens = lens
        this.aspect = aspect
        c.cameraInfo.zoomState.observe(lifecycleOwner) { z ->
            _zoomRange.value = z.minZoomRatio..z.maxZoomRatio
            _zoomRatio.value = z.zoomRatio
        }
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
