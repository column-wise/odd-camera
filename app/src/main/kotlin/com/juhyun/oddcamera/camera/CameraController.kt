package com.juhyun.oddcamera.camera

import android.content.Context
import android.util.Log
import android.util.Rational
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.juhyun.oddcamera.render.RenderThread

enum class Aspect { PHOTO_4_3, VIDEO_16_9 }

/** Binds CameraX use cases to the lifecycle owner; preview frames go to the RenderThread. */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val renderThread: RenderThread,
) {
    /** lens: CameraSelector.LENS_FACING_BACK / LENS_FACING_FRONT. */
    fun bind(lens: Int, aspect: Aspect) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                bindNow(future.get(), lens, aspect)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
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
            .build()
        preview.setSurfaceProvider(renderThread.cameraSurfaceProvider())
        // Task 9 adds ImageCapture to this group; the shared ViewPort keeps preview crop == capture crop.
        val group = UseCaseGroup.Builder()
            .setViewPort(ViewPort.Builder(ratio, Surface.ROTATION_0).build())
            .addUseCase(preview)
            .build()
        renderThread.mirror = lens == CameraSelector.LENS_FACING_FRONT
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.Builder().requireLensFacing(lens).build(), group)
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
