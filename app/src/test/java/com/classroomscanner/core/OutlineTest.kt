package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskOutlineTest {

    /** 10x10 mask with a filled square at columns/rows 3..6. */
    private fun squareMask(): FloatArray = FloatArray(100) { i ->
        val x = i % 10
        val y = i / 10
        if (x in 3..6 && y in 3..6) 1f else 0f
    }

    @Test
    fun squareGivesAClosedLookingOutlineAroundIt() {
        val segs = MaskOutline.segments(squareMask(), 10, 10, 0f, 0f, 10f, 10f, maxCells = 10)
        assertTrue(segs.isNotEmpty())
        assertEquals(0, segs.size % 4)
        // Every point lies on the border band between inside (3..6) and outside (2 or 7).
        for (v in segs.toList()) assertTrue("coordinate $v", v in 2f..7f)
        // A square outline has as many segments on the left half as on the right half.
        val xs = segs.filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(xs.count { it < 4.5f }, xs.count { it > 4.5f })
    }

    @Test
    fun emptyMaskGivesNothing() {
        assertEquals(0, MaskOutline.segments(FloatArray(100), 10, 10, 0f, 0f, 10f, 10f, maxCells = 10).size)
    }

    @Test
    fun regionLimitsTheSearch() {
        // Region far from the square: nothing found.
        assertEquals(0, MaskOutline.segments(squareMask(), 10, 10, 8f, 8f, 10f, 10f, maxCells = 10).size)
    }

    @Test
    fun fullRegionIsOutlinedAtItsBorder() {
        val segs = MaskOutline.segments(FloatArray(100) { 1f }, 10, 10, 2f, 2f, 6f, 6f, maxCells = 8)
        assertTrue(segs.isNotEmpty())
        for (v in segs.toList()) assertTrue("coordinate $v", v in 1f..7f)
    }
}

class OutlineTrackerTest {

    private val square = floatArrayOf(10f, 10f, 20f, 10f)

    @Test
    fun matchingBoxReturnsOutlineMovedWithTheBox() {
        val t = OutlineTracker()
        t.replace(listOf(OutlineTracker.Entry("chair", floatArrayOf(0f, 0f, 40f, 40f), square)))
        val moved = t.lookup("chair", floatArrayOf(5f, 0f, 45f, 40f))!!
        assertEquals(listOf(15f, 10f, 25f, 10f), moved.toList())
    }

    @Test
    fun otherLabelOrFarBoxHasNoOutline() {
        val t = OutlineTracker()
        t.replace(listOf(OutlineTracker.Entry("chair", floatArrayOf(0f, 0f, 40f, 40f), square)))
        assertNull(t.lookup("tv", floatArrayOf(0f, 0f, 40f, 40f)))
        assertNull(t.lookup("chair", floatArrayOf(100f, 100f, 140f, 140f)))
    }

    @Test
    fun bestOverlapWins() {
        val t = OutlineTracker()
        t.replace(
            listOf(
                OutlineTracker.Entry("chair", floatArrayOf(0f, 0f, 40f, 40f), floatArrayOf(1f, 1f, 1f, 1f)),
                OutlineTracker.Entry("chair", floatArrayOf(30f, 0f, 70f, 40f), floatArrayOf(2f, 2f, 2f, 2f)),
            )
        )
        assertEquals(2f, t.lookup("chair", floatArrayOf(30f, 0f, 70f, 40f))!![0], 0f)
    }

    @Test
    fun iouOfBoxes() {
        assertEquals(1f, OutlineTracker.iou(floatArrayOf(0f, 0f, 10f, 10f), floatArrayOf(0f, 0f, 10f, 10f)), 1e-4f)
        assertEquals(0f, OutlineTracker.iou(floatArrayOf(0f, 0f, 10f, 10f), floatArrayOf(20f, 20f, 30f, 30f)), 1e-4f)
        assertEquals(1f / 3f, OutlineTracker.iou(floatArrayOf(0f, 0f, 10f, 10f), floatArrayOf(5f, 0f, 15f, 10f)), 1e-4f)
    }
}
