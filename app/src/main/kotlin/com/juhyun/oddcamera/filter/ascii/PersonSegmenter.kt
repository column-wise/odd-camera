package com.juhyun.oddcamera.filter.ascii

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Person confidence 0..255 (unsigned bytes), row 0 = image top, row-major w×h. */
class Mask(val data: ByteArray, val w: Int, val h: Int)

/** The mask rotated clockwise by deg (0/90/180/270), same convention as rotateBoxes. */
fun rotateMask(m: Mask, deg: Int): Mask {
    val r = Math.floorMod(deg, 360)
    if (r == 0) return m
    val w = m.w; val h = m.h
    val nw = if (r == 180) w else h
    val out = ByteArray(m.data.size)
    for (y in 0 until h) for (x in 0 until w) {
        val (nx, ny) = when (r) { 90 -> (h - 1 - y) to x; 180 -> (w - 1 - x) to (h - 1 - y); else -> y to (w - 1 - x) }
        out[ny * nw + nx] = m.data[y * w + x]
    }
    return Mask(out, nw, if (r == 180) h else w)
}

/**
 * ML Kit selfie segmentation (on-device, any visible people). Preview: [submit] (stream mode) is non-blocking and
 * drops input while a frame is in flight; [latest] is the newest mask. Stills: [segmentBlocking] (single-image mode).
 * Any ML Kit failure is logged once and leaves the mask null (no glyph region), never crashes.
 */
class PersonSegmenter {
    @Volatile var latest: Mask? = null
        private set
    private val busy = AtomicBoolean(false)
    private var stream: Segmenter? = null
    private var still: Segmenter? = null
    @Volatile private var failed = false
    @Volatile private var warned = false
    private val direct = Executor { it.run() } // listeners run on ML Kit's worker, never on the GL/main thread

    private fun client(single: Boolean): Segmenter? {
        if (failed) return null
        return try {
            if (single) still ?: create(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).also { still = it }
            else stream ?: create(SelfieSegmenterOptions.STREAM_MODE).also { stream = it }
        } catch (t: Throwable) { fail(t); null }
    }

    private fun create(mode: Int) =
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(mode).build())

    /** Init failure: disables segmentation for good. */
    private fun fail(t: Throwable) {
        warn(t)
        failed = true
    }

    private fun warn(t: Throwable) {
        if (!warned) Log.w(TAG, "person segmentation failed; ASCII person region stays empty", t)
        warned = true
    }

    /** rgba: image-order RGBA bytes, w×h. Returns at once; the result lands in [latest]. */
    fun submit(rgba: ByteArray, w: Int, h: Int) {
        val c = client(single = false) ?: return
        if (!busy.compareAndSet(false, true)) return
        try {
            c.process(InputImage.fromBitmap(bitmap(rgba, w, h), 0))
                .addOnSuccessListener(direct) { latest = toMask(it); busy.set(false) }
                .addOnFailureListener(direct) { warn(it); busy.set(false) }
        } catch (t: Throwable) { warn(t); busy.set(false) }
    }

    /** Still capture; blocks up to 1500 ms (not on the main thread). Null on timeout or failure. */
    fun segmentBlocking(rgba: ByteArray, w: Int, h: Int): Mask? {
        val c = client(single = true) ?: return null
        return try {
            toMask(Tasks.await(c.process(InputImage.fromBitmap(bitmap(rgba, w, h), 0)), 1500, TimeUnit.MILLISECONDS))
        } catch (t: Throwable) { warn(t); null }
    }

    fun close() {
        stream?.close(); still?.close()
        stream = null; still = null
        latest = null
    }

    private fun bitmap(rgba: ByteArray, w: Int, h: Int) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(rgba)) }

    private fun toMask(m: SegmentationMask): Mask {
        val buf = m.buffer.apply { rewind() } // floats 0..1 in ML Kit's byte order
        return Mask(ByteArray(m.width * m.height) { (buf.getFloat() * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() },
            m.width, m.height)
    }

    private companion object { const val TAG = "PersonSegmenter" }
}
