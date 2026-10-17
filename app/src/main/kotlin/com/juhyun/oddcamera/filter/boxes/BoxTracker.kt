package com.juhyun.oddcamera.filter.boxes

/** Normalized 0..1 frame coordinates. */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float, val id: Int, val mark: Boolean)

class BoxTracker(private val smoothing: Float = 0.5f) {
    private var parents = emptyList<Box>()   // previous frame's pre-split boxes
    private var nextId = 0

    fun reset() { parents = emptyList() }

    fun update(blobs: List<Blob>, w: Int, h: Int, maxBoxes: Int): List<Box> {
        val free = parents.toMutableList()
        val newParents = ArrayList<Box>()
        val out = ArrayList<Box>()
        for (b in blobs.take(maxBoxes)) {
            val x0 = ((b.x0 - 1).coerceAtLeast(0)).toFloat() / w
            val y0 = ((b.y0 - 1).coerceAtLeast(0)).toFloat() / h
            val x1 = ((b.x1 + 2).coerceAtMost(w)).toFloat() / w
            val y1 = ((b.y1 + 2).coerceAtMost(h)).toFloat() / h
            val target = Box(x0, y0, x1 - x0, y1 - y0, -1, false)
            val prev = free.maxByOrNull { iou(it, target) }?.takeIf { iou(it, target) > 0.2f }
            val p = if (prev != null) {
                free.remove(prev)
                fun ease(a: Float, t: Float) = a + (t - a) * smoothing
                target.copy(ease(prev.x, target.x), ease(prev.y, target.y), ease(prev.w, target.w), ease(prev.h, target.h), prev.id)
            } else target.copy(id = nextId++)
            newParents += p
            if (p.w * p.h > 0.02f) {
                val hw = p.w / 2; val hh = p.h / 2
                for (k in 0..3) {
                    val id = p.id * 4 + k
                    out += Box(p.x + (k % 2) * hw, p.y + (k / 2) * hh, hw, hh, id, id % 3 == 0)
                }
            } else out += p.copy(mark = p.id % 3 == 0)
        }
        parents = newParents
        return out
    }

    private fun iou(a: Box, b: Box): Float {
        val iw = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
        val ih = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return inter / (a.w * a.h + b.w * b.h - inter)
    }
}
