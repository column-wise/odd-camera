package com.juhyun.oddcamera.filter.boxes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxTrackerTest {
    @Test fun maxBoxesCapsOutput() {
        val blobs = (0 until 50).map { i -> val x = (i % 10) * 4; val y = (i / 10) * 4; Blob(x, y, x + 1, y, 2) }
        assertEquals(10, BoxTracker().update(blobs, 100, 100, 10).size)
    }

    @Test fun stableIdAndEasingAcrossFrames() {
        val t = BoxTracker(0.5f)
        val a = t.update(listOf(Blob(10, 10, 19, 19, 100)), 100, 100, 40).single()
        val b = t.update(listOf(Blob(12, 10, 21, 19, 100)), 100, 100, 40).single()
        assertEquals(a.id, b.id); assertTrue(b.x > a.x && b.x < 0.13f)
    }

    @Test fun largeBlobSplitsIntoFour() {
        val t = BoxTracker()
        val boxes = t.update(listOf(Blob(30, 30, 59, 59, 900)), 100, 100, 40)
        assertEquals(4, boxes.size)
        val base = boxes[0].id / 4
        assertEquals((0..3).map { base * 4 + it }, boxes.map { it.id })
        // quadrants tile the padded parent (x 0.29..0.61)
        assertEquals(0.29f, boxes.minOf { it.x }, 1e-4f)
        assertEquals(0.61f, boxes.maxOf { it.x + it.w }, 1e-4f)
        // ids stay stable on the next frame (matched against the parent)
        assertEquals(boxes.map { it.id }, t.update(listOf(Blob(31, 30, 60, 59, 900)), 100, 100, 40).map { it.id })
    }

    @Test fun paddingClampedToFrame() {
        val b = BoxTracker().update(listOf(Blob(0, 0, 9, 9, 100)), 100, 100, 5).single()
        assertEquals(0f, b.x, 0f); assertEquals(0f, b.y, 0f)
    }

    @Test fun resetForgetsPreviousBoxes() {
        val t = BoxTracker()
        val a = t.update(listOf(Blob(10, 10, 19, 19, 100)), 100, 100, 5).single()
        t.reset()
        assertTrue(t.update(listOf(Blob(10, 10, 19, 19, 100)), 100, 100, 5).single().id != a.id)
    }
}
