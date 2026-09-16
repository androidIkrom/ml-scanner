package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleMathTest {
    private val eps = 0.001f

    @Test
    fun normalizeWrapsIntoZeroTo360() {
        assertEquals(350f, AngleMath.normalize(-10f), eps)
        assertEquals(10f, AngleMath.normalize(370f), eps)
        assertEquals(0f, AngleMath.normalize(360f), eps)
        assertEquals(0f, AngleMath.normalize(0f), eps)
        assertTrue(AngleMath.normalize(-0.00001f) < 360f)
    }

    @Test
    fun diffTakesShortestWay() {
        assertEquals(2f, AngleMath.diff(1f, 359f), eps)
        assertEquals(-2f, AngleMath.diff(359f, 1f), eps)
        assertEquals(180f, AngleMath.diff(180f, 0f), eps)
        assertEquals(-90f, AngleMath.diff(0f, 90f), eps)
    }

    @Test
    fun sector8Boundaries() {
        assertEquals(Sector8.FRONT, AngleMath.sector8(0f))
        assertEquals(Sector8.FRONT, AngleMath.sector8(22.4f))
        assertEquals(Sector8.FRONT_RIGHT, AngleMath.sector8(22.5f))
        assertEquals(Sector8.RIGHT, AngleMath.sector8(90f))
        assertEquals(Sector8.BEHIND_RIGHT, AngleMath.sector8(135f))
        assertEquals(Sector8.BEHIND, AngleMath.sector8(180f))
        assertEquals(Sector8.BEHIND_LEFT, AngleMath.sector8(225f))
        assertEquals(Sector8.LEFT, AngleMath.sector8(270f))
        assertEquals(Sector8.FRONT_LEFT, AngleMath.sector8(337.4f))
        assertEquals(Sector8.FRONT, AngleMath.sector8(337.5f))
        assertEquals(Sector8.FRONT, AngleMath.sector8(-10f))
    }

    @Test
    fun sector4Boundaries() {
        assertEquals(Sector4.FRONT, AngleMath.sector4(44.9f))
        assertEquals(Sector4.RIGHT, AngleMath.sector4(45f))
        assertEquals(Sector4.RIGHT, AngleMath.sector4(134.9f))
        assertEquals(Sector4.BEHIND, AngleMath.sector4(135f))
        assertEquals(Sector4.LEFT, AngleMath.sector4(225f))
        assertEquals(Sector4.LEFT, AngleMath.sector4(314.9f))
        assertEquals(Sector4.FRONT, AngleMath.sector4(315f))
    }

    @Test
    fun weightedMeanHandlesWrapAround() {
        assertEquals(42f, AngleMath.weightedMean(123f, 0, 42f), eps)
        assertEquals(0f, AngleMath.weightedMean(350f, 1, 10f), eps)
        assertEquals(20f, AngleMath.weightedMean(10f, 1, 30f), eps)
    }
}
