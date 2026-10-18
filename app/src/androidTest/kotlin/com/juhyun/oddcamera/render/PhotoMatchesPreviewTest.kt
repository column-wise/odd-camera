package com.juhyun.oddcamera.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juhyun.oddcamera.filter.Filter
import com.juhyun.oddcamera.filter.FilterRenderer
import com.juhyun.oddcamera.filter.FrameAnalysis
import com.juhyun.oddcamera.filter.ParamValues
import com.juhyun.oddcamera.filter.ascii.AsciiFilter
import com.juhyun.oddcamera.filter.boxes.LightBoxesAnalysis
import com.juhyun.oddcamera.filter.boxes.LightBoxesFilter
import com.juhyun.oddcamera.gl.EglCore
import com.juhyun.oddcamera.gl.GlUtil
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R8: the photo path (PhotoRenderer, 4× source, snapshot taken from the preview) must look like the preview
 * (AnalysisReader + FilterPass at 1×) once both are downscaled to 90 px wide.
 */
@RunWith(AndroidJUnit4::class)
class PhotoMatchesPreviewTest {
    private lateinit var egl: EglCore
    private lateinit var photo: PhotoRenderer

    @Before fun setUp() {
        egl = EglCore()
        egl.makeCurrent(egl.createOffscreenSurface(1, 1))
        photo = PhotoRenderer()
    }

    @After fun tearDown() { photo.release(); egl.release() }

    /** 360×270 image-order scene: x/y gradient (asymmetric) plus three bright "lamps". */
    private fun scene(): Bitmap {
        val px = IntArray(W * H) { i ->
            val x = i % W; val y = i / W
            val lamp = (x in 40 until 64 && y in 30 until 50) || (x in 250 until 280 && y in 180 until 204) ||
                (x in 300 until 310 && y in 40 until 50)
            if (lamp) Color.WHITE else Color.rgb(20 + x / 3, 30 + y / 3, 90)
        }
        return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888)
    }

    private fun Bitmap.transformed(m: Matrix) = Bitmap.createBitmap(this, 0, 0, width, height, m, false)
    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private class Preview(val image: Bitmap, val params: ParamValues, val analysis: FrameAnalysis)

    /** Preview path: upright frame texture -> AnalysisReader -> analyze -> FilterPass at frame size. */
    private fun preview(filter: Filter, r: FilterRenderer, src: Bitmap, params: ParamValues,
                        analysis: FrameAnalysis? = null): Preview {
        val w = src.width; val h = src.height
        val tex = uploadUpright(src)
        val reader = AnalysisReader()
        val a = analysis ?: filter.analyze(reader.read(tex, w, h, 160), params, null)
        reader.release()
        val out = GlUtil.Fbo(w, h)
        FilterPass.render(r, tex, out.id, w, h, params, a)
        GlUtil.checkGlError("preview")
        val img = Bitmap.createBitmap(readImage(out.id, w, h), w, h, Bitmap.Config.ARGB_8888)
        out.release()
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        return Preview(img, params, a)
    }

    private fun mad(a: Bitmap, b: Bitmap) = downscaledMad(a.pixels(), a.width, a.height, b.pixels(), b.width, b.height)

    private fun params(filter: Filter) = ParamValues(filter.params).apply {
        if (filter === AsciiFilter) { set("region", "bright"); set("topPercent", 20f) } // glyphs over a good part
    }

    /** Compares preview vs 4× photo for one filter/mirror/rotation; returns the MAD. */
    private fun compare(filter: Filter, mirror: Boolean, rotationDeg: Int): Double {
        val r = filter.createRenderer().apply { init() }
        val sensorImage = scene()   // what ImageCapture delivers (never mirrored)
        val previewSrc = if (mirror) sensorImage.transformed(Matrix().apply { postScale(-1f, 1f) }) else sensorImage
        val p = preview(filter, r, previewSrc, params(filter))
        if (filter === LightBoxesFilter) assertTrue("no boxes", (p.analysis as LightBoxesAnalysis).boxes.size >= 3)

        val big = Bitmap.createScaledBitmap(sensorImage, W * 4, H * 4, false)
        val shot = photo.render(big, CaptureSnapshot(filter, p.params, p.analysis, mirror, rotationDeg), r)
        GlUtil.checkGlError("photo")

        val rot = Matrix().apply { postRotate(rotationDeg.toFloat()) }
        val expected = when {
            rotationDeg == 0 -> p.image
            // Glyphs stay upright in the output: expect the preview of the device-rotated scene (same analysis).
            filter === AsciiFilter -> preview(filter, r, previewSrc.transformed(rot), p.params, p.analysis).image
            else -> p.image.transformed(rot)
        }
        r.release()
        val mad = mad(expected, shot)
        assertTrue("${filter.id} mirror=$mirror rot=$rotationDeg: mean abs diff $mad >= 8", mad < 8)
        return mad
    }

    @Test fun lightBoxes() { compare(LightBoxesFilter, false, 0) }
    @Test fun ascii() { compare(AsciiFilter, false, 0) }
    @Test fun lightBoxesMirrored() { compare(LightBoxesFilter, true, 0) }
    @Test fun asciiMirrored() { compare(AsciiFilter, true, 0) }
    @Test fun lightBoxesRotated90() { compare(LightBoxesFilter, false, 90) }
    @Test fun asciiRotated90() { compare(AsciiFilter, false, 90) }
    @Test fun lightBoxesMirroredRotated270() { compare(LightBoxesFilter, true, 270) }

    /** R13: a still exposed darker than the preview (×0.7) still gets the preview's ASCII coverage. */
    @Test fun asciiCoverageFollowsTheStillNotTheAbsoluteThreshold() {
        val r = AsciiFilter.createRenderer().apply { init() }
        val src = scene()
        val p = preview(AsciiFilter, r, src, params(AsciiFilter))
        val darker = Bitmap.createBitmap(src.pixels().map { c ->
            Color.rgb((Color.red(c) * 0.7f).toInt(), (Color.green(c) * 0.7f).toInt(), (Color.blue(c) * 0.7f).toInt())
        }.toIntArray(), W, H, Bitmap.Config.ARGB_8888)
        val shot = photo.render(Bitmap.createScaledBitmap(darker, W * 4, H * 4, false),
            CaptureSnapshot(AsciiFilter, p.params, p.analysis, false, 0), r)
        r.release()
        // Coverage = share of replaced-cell pixels (white or red glyph; this scene's gray background stays < 110).
        fun coverage(b: Bitmap) = b.pixels().count { c ->
            Color.red(c) > 150
        }.toDouble() / (b.width * b.height)
        val cp = coverage(p.image); val cs = coverage(shot)
        assertTrue("preview coverage $cp too small for a meaningful check", cp > 0.1)
        assertTrue("photo coverage $cs vs preview $cp", Math.abs(cs - cp) < 0.03)
    }

    /**
     * The outlines are too thin to move the MAD much, so check them alone: same analysis over a black source,
     * outline overlap (IoU of the 90 px-wide downscales) between photo and rotated/mirrored preview.
     */
    @Test fun boxOutlinesLandWhereThePreviewDrewThem() {
        for ((mirror, rot) in listOf(false to 0, true to 0, false to 90, false to 180, true to 270)) {
            val r = LightBoxesFilter.createRenderer().apply { init() }
            val src = scene()
            val p = preview(LightBoxesFilter, r, if (mirror) src.transformed(Matrix().apply { postScale(-1f, 1f) }) else src,
                params(LightBoxesFilter))
            val black = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            val expected = preview(LightBoxesFilter, r, black, p.params, p.analysis).image
                .transformed(Matrix().apply { postRotate(rot.toFloat()) })
            val shot = photo.render(Bitmap.createScaledBitmap(black, W * 4, H * 4, false),
                CaptureSnapshot(LightBoxesFilter, p.params, p.analysis, mirror, rot), r)
            r.release()
            val a = down90(expected); val b = down90(shot)
            val iou = a.indices.sumOf { minOf(a[it], b[it]) } / a.indices.sumOf { maxOf(a[it], b[it]) }
            assertTrue("mirror=$mirror rot=$rot outline IoU $iou < 0.5", iou > 0.5)
        }
    }

    /** Box-downscale to 90 px wide, RGB average. */
    private fun down90(b: Bitmap): DoubleArray {
        val px = b.pixels(); val f = b.width / 90; val h = b.height / f
        return DoubleArray(90 * h) { i ->
            val x = i % 90; val y = i / 90
            var s = 0.0
            for (j in 0 until f) for (k in 0 until f) {
                val c = px[(y * f + j) * b.width + x * f + k]
                s += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3.0
            }
            s / (f * f)
        }
    }

    private companion object { const val W = 360; const val H = 270 }
}
