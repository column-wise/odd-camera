package com.juhyun.oddcamera.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.juhyun.oddcamera.render.CaptureSnapshot
import com.juhyun.oddcamera.render.RenderThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Still capture -> same filter/params/analysis as the preview at the shutter moment -> JPEG in MediaStore. */
class PhotoCapturer(
    private val renderThread: RenderThread,
    private val imageCapture: ImageCapture,
    private val saver: MediaSaver,
) {
    /** snapshot must be taken (renderThread.snapshotForCapture()) at the shutter moment, before calling this. */
    suspend fun capture(snapshot: CaptureSnapshot): Uri {
        val image = takePicture()
        val src = withContext(Dispatchers.Default) { image.use(::decodeUpright) }
        val out = withContext(Dispatchers.Default) {
            try { renderThread.renderPhoto(src, snapshot) } finally { src.recycle() }
        }
        return withContext(Dispatchers.IO) {
            try {
                saver.savePhoto { check(out.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "JPEG encode failed" } }
            } finally {
                out.recycle()
            }
        }
    }

    private suspend fun takePicture(): ImageProxy = suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(Dispatchers.Default.asExecutor(), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                cont.resume(image) { _, img, _ -> img.close() }
            }
            override fun onError(e: ImageCaptureException) = cont.resumeWithException(e)
        })
    }

    private companion object {
        /** JPEG ImageProxy -> Bitmap with the ViewPort crop and sensor rotation applied (what the preview frame shows, unmirrored). */
        fun decodeUpright(image: ImageProxy): Bitmap {
            val buf = image.planes[0].buffer.apply { rewind() }
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "JPEG decode failed" }
            val crop = Rect(image.cropRect)
            check(crop.intersect(0, 0, decoded.width, decoded.height)) { "empty crop ${image.cropRect}" }
            val rot = image.imageInfo.rotationDegrees
            Log.i(TAG, "still ${decoded.width}x${decoded.height} crop $crop rotation $rot")
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            val out = Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width(), crop.height(), m, true)
            if (out !== decoded) decoded.recycle()
            return out
        }

        const val TAG = "PhotoCapturer"
    }
}
