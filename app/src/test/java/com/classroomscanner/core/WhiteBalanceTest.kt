package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WhiteBalanceTest {
    private val eps = 0.001f

    @Test
    fun neutralFrameKeepsColors() {
        val g = WhiteBalance.gains(100f, 100f, 100f)
        assertEquals(1f, g.r, eps)
        assertEquals(1f, g.g, eps)
        assertEquals(1f, g.b, eps)
        assertEquals(0x336699, WhiteBalance.apply(0x336699, g))
    }

    @Test
    fun warmCastIsPulledTowardGray() {
        // Yellowish lamp: red and green high, blue low.
        val g = WhiteBalance.gains(150f, 120f, 90f)
        assertEquals(120f / 150f, g.r, eps)
        assertEquals(1f, g.g, eps)
        assertEquals(120f / 90f, g.b, eps)
        assertEquals(0x787878, WhiteBalance.apply(0x96785A, g))
    }

    @Test
    fun gainsAreClamped() {
        val g = WhiteBalance.gains(250f, 100f, 10f)
        assertEquals(0.6f, g.r, eps)
        assertEquals(1.6f, g.b, eps)
    }

    @Test
    fun blackFrameDoesNotDivideByZero() {
        val g = WhiteBalance.gains(0f, 0f, 0f)
        assertEquals(1f, g.r, eps)
        assertEquals(1f, g.g, eps)
        assertEquals(1f, g.b, eps)
    }

    @Test
    fun applyClampsTo255() {
        val g = WhiteBalance.Gains(1.6f, 1.6f, 1.6f)
        assertEquals(0xFFFFFF, WhiteBalance.apply(0xC8C8C8, g))
    }
}
