package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTrackerTest {

    @Test
    fun startsEmpty() {
        val t = CoverageTracker()
        assertEquals(0, t.percent())
        assertFalse(t.isComplete())
    }

    @Test
    fun markCoversOneBin() {
        val t = CoverageTracker()
        t.mark(5f)
        assertEquals(1, t.coveredBins())
        assertEquals(2, t.percent())
        assertTrue(t.snapshot()[0])
    }

    @Test
    fun markArcFillsBinsBetween() {
        val t = CoverageTracker()
        t.markArc(0f, 40f)
        assertEquals(5, t.coveredBins())
    }

    @Test
    fun markArcCrossesZero() {
        val t = CoverageTracker()
        t.markArc(355f, 5f)
        assertEquals(2, t.coveredBins())
        assertTrue(t.snapshot()[35])
        assertTrue(t.snapshot()[0])
    }

    @Test
    fun largeJumpOnlyMarksTarget() {
        val t = CoverageTracker()
        t.markArc(0f, 100f)
        assertEquals(1, t.coveredBins())
        assertTrue(t.snapshot()[10])
    }

    @Test
    fun fullCircleIsComplete() {
        val t = CoverageTracker()
        for (a in 0 until 360 step 5) t.markArc(a.toFloat(), (a + 5).toFloat())
        assertTrue(t.isComplete())
        assertEquals(100, t.percent())
    }

    @Test
    fun snapshotIsACopy() {
        val t = CoverageTracker()
        t.snapshot()[3] = true
        assertEquals(0, t.coveredBins())
    }
}
