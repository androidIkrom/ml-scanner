package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BoxGeometryTest {
    private val eps = 0.001f

    @Test
    fun noRotationUsesX() {
        assertEquals(0.125f, BoxGeometry.horizontalCenter(0f, 0f, 100f, 50f, 400, 300, 0), eps)
    }

    @Test
    fun rotation90UsesInvertedY() {
        // Unrotated buffer 640x480; box vertical center y=60 -> upright x fraction 1 - 60/480
        assertEquals(0.875f, BoxGeometry.horizontalCenter(0f, 0f, 10f, 120f, 640, 480, 90), eps)
    }

    @Test
    fun rotation180UsesInvertedX() {
        assertEquals(0.875f, BoxGeometry.horizontalCenter(0f, 0f, 100f, 50f, 400, 300, 180), eps)
    }

    @Test
    fun rotation270UsesY() {
        assertEquals(0.125f, BoxGeometry.horizontalCenter(0f, 0f, 10f, 120f, 640, 480, 270), eps)
    }

    @Test
    fun objectAngleAddsFovOffsetAndWraps() {
        assertEquals(20f, BoxGeometry.objectAngle(350f, 1f, 60f), eps)
        assertEquals(340f, BoxGeometry.objectAngle(10f, 0f, 60f), eps)
        assertEquals(90f, BoxGeometry.objectAngle(90f, 0.5f, 65f), eps)
    }
}
