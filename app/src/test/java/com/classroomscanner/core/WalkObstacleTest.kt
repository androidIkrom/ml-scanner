package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthObstaclesTest {
    private val width = 9
    private val height = 10

    /** Depth grid in metres; row 0 is the top of the image. */
    private fun grid(vararg rows: FloatArray): FloatArray {
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val row = rows.getOrElse(y) { FloatArray(width) { 6f } }
            for (x in 0 until width) out[y * width + x] = row.getOrElse(x) { 6f }
        }
        return out
    }

    @Test
    fun emptyCorridorHasNoObstacle() {
        val depths = grid()
        assertNull(DepthObstacles.nearest(depths, width, height)[WalkZone.AHEAD])
    }

    @Test
    fun aWallAheadIsFound() {
        // Rows 3..6 are the band the walker's body passes through.
        val close = FloatArray(width) { 1.2f }
        val depths = grid(FloatArray(width) { 6f }, FloatArray(width) { 6f }, FloatArray(width) { 6f },
            close, close, close, close)
        val found = DepthObstacles.nearest(depths, width, height)
        assertEquals(1.2f, found[WalkZone.AHEAD]!!, 0.01f)
    }

    @Test
    fun somethingOnlyOnTheLeft() {
        val row = FloatArray(width) { if (it < 3) 1.5f else 6f }
        val depths = grid(FloatArray(width) { 6f }, FloatArray(width) { 6f }, FloatArray(width) { 6f },
            row, row, row, row)
        val found = DepthObstacles.nearest(depths, width, height)
        assertEquals(1.5f, found[WalkZone.LEFT]!!, 0.01f)
        assertNull(found[WalkZone.AHEAD])
    }

    @Test
    fun missingAndAbsurdReadingsAreIgnored() {
        val row = FloatArray(width) { 0f }
        val depths = grid(FloatArray(width) { 0f }, FloatArray(width) { 0f }, FloatArray(width) { 0f },
            row, row, row, row)
        assertNull(DepthObstacles.nearest(depths, width, height)[WalkZone.AHEAD])
    }

    @Test
    fun aFewStrayPixelsAreNotAnObstacle() {
        // One close pixel in a far band is noise, not a wall.
        val row = FloatArray(width) { if (it == 4) 0.8f else 6f }
        val depths = grid(FloatArray(width) { 6f }, FloatArray(width) { 6f }, FloatArray(width) { 6f },
            row, row, row, row)
        assertNull(DepthObstacles.nearest(depths, width, height)[WalkZone.AHEAD])
    }
}

class HazardConfirmerTest {
    private val chair = Hazard("chair", 2f, WalkZone.AHEAD)
    private val cat = Hazard("cat", 2f, WalkZone.LEFT)

    @Test
    fun thingsSeenOnceAreNotAnnounced() {
        val confirmer = HazardConfirmer(minHits = 3, window = 5)
        assertTrue(confirmer.confirmed(listOf(chair)).isEmpty())
        assertTrue(confirmer.confirmed(listOf(chair)).isEmpty())
    }

    @Test
    fun thingsSeenInMostFramesAreAnnounced() {
        val confirmer = HazardConfirmer(minHits = 3, window = 5)
        confirmer.confirmed(listOf(chair))
        confirmer.confirmed(listOf(chair))
        assertEquals(listOf(chair), confirmer.confirmed(listOf(chair)))
    }

    @Test
    fun flickeringThingsStayQuiet() {
        val confirmer = HazardConfirmer(minHits = 3, window = 5)
        confirmer.confirmed(listOf(cat))
        confirmer.confirmed(emptyList())
        confirmer.confirmed(listOf(cat))
        confirmer.confirmed(emptyList())
        assertTrue(confirmer.confirmed(emptyList()).isEmpty())
    }

    @Test
    fun theNewestDistanceIsKept() {
        val confirmer = HazardConfirmer(minHits = 2, window = 3)
        confirmer.confirmed(listOf(chair))
        val closer = chair.copy(metres = 1.1f)
        assertEquals(listOf(closer), confirmer.confirmed(listOf(closer)))
    }

    @Test
    fun oldThingsAreForgotten() {
        val confirmer = HazardConfirmer(minHits = 2, window = 2)
        confirmer.confirmed(listOf(chair))
        confirmer.confirmed(emptyList())
        confirmer.confirmed(emptyList())
        assertTrue(confirmer.confirmed(listOf(chair)).isEmpty())
    }
}

class HazardPolicyNarrowingTest {
    @Test
    fun onlyThingsThatCanBeInTheWayCount() {
        assertTrue(HazardPolicy.isHazard("person"))
        assertTrue(HazardPolicy.isHazard("car"))
        assertTrue(HazardPolicy.isHazard("bicycle"))
        assertTrue(HazardPolicy.isHazard("chair"))
        assertTrue(HazardPolicy.isHazard("dog"))
        // Classes the detector reports indoors by mistake are left out.
        assertFalse(HazardPolicy.isHazard("toilet"))
        assertFalse(HazardPolicy.isHazard("tv"))
        assertFalse(HazardPolicy.isHazard("cat"))
        assertFalse(HazardPolicy.isHazard("refrigerator"))
        assertFalse(HazardPolicy.isHazard("traffic light"))
    }
}
