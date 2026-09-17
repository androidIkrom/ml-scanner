package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchGuideTest {
    @Test
    fun directionBands() {
        assertEquals("far left", SearchGuide.direction(0.1f))
        assertEquals("left", SearchGuide.direction(0.3f))
        assertEquals("ahead", SearchGuide.direction(0.5f))
        assertEquals("right", SearchGuide.direction(0.7f))
        assertEquals("far right", SearchGuide.direction(0.9f))
    }

    @Test
    fun centered() {
        assertTrue(SearchGuide.isCentered(0.45f))
        assertFalse(SearchGuide.isCentered(0.3f))
    }

    @Test
    fun beepGetsFasterTowardCenter() {
        assertEquals(150L, SearchGuide.beepIntervalMs(0.5f))
        assertEquals(1000L, SearchGuide.beepIntervalMs(0f))
        assertEquals(1000L, SearchGuide.beepIntervalMs(1f))
        assertEquals(575L, SearchGuide.beepIntervalMs(0.25f))
    }
}

class SearchTrackerTest {
    @Test
    fun announcesFoundThenDirectionChangesThenLost() {
        val t = SearchTracker("my bag", lostAfterMs = 3_000, repeatMs = 2_000)
        assertNull(t.update(0, null))
        assertEquals("Found my bag, left.", t.update(100, 0.3f))
        assertNull(t.update(200, 0.3f))
        // Changed but too soon.
        assertNull(t.update(1_000, 0.5f))
        assertEquals("Ahead.", t.update(2_200, 0.5f))
        assertNull(t.update(3_000, null))
        assertEquals("Lost it. Turn slowly.", t.update(5_300, null))
        assertNull(t.update(6_000, null))
        assertEquals("Found my bag, far right.", t.update(6_100, 0.95f))
    }
}
