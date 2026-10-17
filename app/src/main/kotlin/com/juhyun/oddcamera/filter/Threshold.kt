package com.juhyun.oddcamera.filter

fun lumaHistogram(luma: ByteArray): IntArray {
    val h = IntArray(256)
    for (b in luma) h[b.toInt() and 0xFF]++
    return h
}

/**
 * Smallest luma T such that pixels with luma > T are <= topPercent % of total.
 * Returns 256 (nothing passes) for an empty histogram or when maxLuma - medianLuma < minContrast.
 */
fun percentileThreshold(hist: IntArray, topPercent: Float, minContrast: Int = 24): Int {
    val total = hist.sum()
    if (total == 0) return 256
    val max = hist.indexOfLast { it > 0 }
    var cum = 0
    var median = 0
    while (median < 255 && cum + hist[median] < (total + 1) / 2) cum += hist[median++]
    if (max - median < minContrast) return 256
    val allowed = total * topPercent / 100f
    var above = 0
    for (t in 255 downTo 0) {          // above = count of luma > t
        if (above + hist[t] > allowed) return t   // hist[t] would push t-1 over budget, so T = t
        above += hist[t]
    }
    return 0
}
