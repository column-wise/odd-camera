package com.juhyun.oddcamera.filter.boxes

/** Inclusive pixel bounds. */
data class Blob(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val area: Int)

/** 4-connected components of `luma > threshold`, sorted by area desc. Iterative flood fill. */
fun detectBlobs(luma: ByteArray, w: Int, h: Int, threshold: Int, minArea: Int = 2): List<Blob> {
    val seen = BooleanArray(w * h)
    val stack = IntArray(w * h)
    val out = ArrayList<Blob>()
    fun on(i: Int) = (luma[i].toInt() and 0xFF) > threshold
    for (start in 0 until w * h) {
        if (seen[start] || !on(start)) continue
        var sp = 0
        stack[sp++] = start; seen[start] = true
        var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var area = 0
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            area++
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            if (x > 0 && !seen[i - 1] && on(i - 1)) { seen[i - 1] = true; stack[sp++] = i - 1 }
            if (x < w - 1 && !seen[i + 1] && on(i + 1)) { seen[i + 1] = true; stack[sp++] = i + 1 }
            if (y > 0 && !seen[i - w] && on(i - w)) { seen[i - w] = true; stack[sp++] = i - w }
            if (y < h - 1 && !seen[i + w] && on(i + w)) { seen[i + w] = true; stack[sp++] = i + w }
        }
        if (area >= minArea) out += Blob(x0, y0, x1, y1, area)
    }
    return out.sortedByDescending { it.area }
}
