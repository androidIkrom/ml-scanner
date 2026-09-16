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

    @Test
    fun touchesOneSideEdge_rotation0_leftEdgeOnly_isTrue() {
        assertEquals(
            true,
            BoxGeometry.touchesOneSideEdge(0f, 0f, 20f, 20f, 400, 300, 0),
        )
    }

    @Test
    fun touchesOneSideEdge_rotation0_rightEdgeOnly_isTrue() {
        assertEquals(
            true,
            BoxGeometry.touchesOneSideEdge(380f, 0f, 400f, 20f, 400, 300, 0),
        )
    }

    @Test
    fun touchesOneSideEdge_rotation0_middle_isFalse() {
        assertEquals(
            false,
            BoxGeometry.touchesOneSideEdge(150f, 0f, 250f, 20f, 400, 300, 0),
        )
    }

    @Test
    fun touchesOneSideEdge_rotation0_spansFullWidth_isFalse() {
        assertEquals(
            false,
            BoxGeometry.touchesOneSideEdge(0f, 0f, 400f, 20f, 400, 300, 0),
        )
    }

    @Test
    fun touchesOneSideEdge_rotation90_topNearZeroMapsToUprightRightEdge_isTrue() {
        // Unrotated buffer 640x480; top=0 -> upright x fraction 1 - 0/480 = 1 (right edge).
        assertEquals(
            true,
            BoxGeometry.touchesOneSideEdge(0f, 0f, 10f, 10f, 640, 480, 90),
        )
    }

    @Test
    fun touchesOneSideEdge_rotation90_middle_isFalse() {
        assertEquals(
            false,
            BoxGeometry.touchesOneSideEdge(0f, 200f, 10f, 280f, 640, 480, 90),
        )
    }
}
