package com.juhyun.oddcamera.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamValuesTest {
    @Test fun paramValuesRoundTripAndRejectsGarbage() {
        val defs = listOf(IntParam("n", "N", 5, 150, 40), ChoiceParam("g", "G", listOf("a" to "A", "b" to "B"), "a"))
        val v = ParamValues(defs); v.set("n", 77); v.set("g", "b")
        val w = ParamValues(defs); w.loadFrom(v.toStringMap() + mapOf("n" to "oops", "zzz" to "1"))
        assertEquals(40, w.int("n")); assertEquals("b", w.string("g"))
    }

    @Test fun paramValuesDefaultsAndSnapshotIsolation() {
        val v = ParamValues(listOf(FloatParam("p", "P", 0f, 10f, 3f, 0.5f)))
        val snap = v.snapshot(); v.set("p", 5f)
        assertEquals(3f, snap.float("p")); assertEquals(5f, v.float("p"))
    }

    @Test fun setClampsAndValidates() {
        val v = ParamValues(listOf(
            FloatParam("f", "F", 0f, 1f, 0.5f, 0.1f), IntParam("i", "I", 1, 9, 5),
            BoolParam("b", "B", false), ChoiceParam("c", "C", listOf("x" to "X"), "x"),
            TextParam("t", "T", "ab", 3)))
        v.set("f", 9f); assertEquals(1f, v.float("f"))
        v.set("i", -4); assertEquals(1, v.int("i"))
        v.set("b", true); assertTrue(v.bool("b"))
        v.set("t", "a😀b😀"); assertEquals("a😀b", v.string("t"))
        assertThrows { v.set("c", "nope") }
        assertThrows { v.set("i", "str") }
        assertThrows { v.set("missing", 1) }
    }

    private fun assertThrows(block: () -> Unit) {
        try { block() } catch (e: IllegalArgumentException) { return }
        throw AssertionError("expected IllegalArgumentException")
    }
}
