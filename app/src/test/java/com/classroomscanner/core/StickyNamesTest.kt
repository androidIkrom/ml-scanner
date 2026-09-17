package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyNamesTest {
    private val laptop = floatArrayOf(100f, 100f, 300f, 250f)
    private val laptopMoved = floatArrayOf(110f, 105f, 310f, 255f)
    private val chair = floatArrayOf(500f, 300f, 600f, 450f)

    @Test
    fun sameObjectKeepsItsTrack() {
        val names = StickyNames()
        val first = names.track(listOf("thing"), listOf(laptop))
        val second = names.track(listOf("thing"), listOf(laptopMoved))
        assertEquals(first, second)
    }

    @Test
    fun differentGroupsOrPlacesGetNewTracks() {
        val names = StickyNames()
        val a = names.track(listOf("thing", "thing"), listOf(laptop, chair))
        assertNotEquals(a[0], a[1])
        val b = names.track(listOf("person"), listOf(laptop))
        assertNotEquals(a[0], b[0])
    }

    @Test
    fun nameIsDecidedAfterEnoughVotesAndThenSticks() {
        val names = StickyNames(confirmHits = 2)
        val id = names.track(listOf("thing"), listOf(laptop))[0]
        names.vote(id, "my new laptop")
        assertFalse(names.isDecided(id))
        assertNull(names.nameOf(id))
        names.track(listOf("thing"), listOf(laptopMoved))
        names.vote(id, "my new laptop")
        assertTrue(names.isDecided(id))
        // Later misses do not undo the decision.
        repeat(5) {
            names.track(listOf("thing"), listOf(laptop))
            names.vote(id, null)
        }
        assertEquals("my new laptop", names.nameOf(id))
    }

    @Test
    fun missesDoNotCountAsVotes() {
        val names = StickyNames(confirmHits = 2)
        val id = names.track(listOf("thing"), listOf(laptop))[0]
        names.vote(id, "my bag")
        names.vote(id, null)
        names.vote(id, "other bag")
        assertFalse(names.isDecided(id))
    }

    @Test
    fun lostTracksAreForgotten() {
        val names = StickyNames(confirmHits = 1, forgetAfterFrames = 2)
        val id = names.track(listOf("thing"), listOf(laptop))[0]
        names.vote(id, "my new laptop")
        repeat(3) { names.track(emptyList(), emptyList()) }
        val again = names.track(listOf("thing"), listOf(laptop))[0]
        assertNotEquals(id, again)
        assertNull(names.nameOf(again))
    }

    @Test
    fun shortGapKeepsTheName() {
        val names = StickyNames(confirmHits = 1, forgetAfterFrames = 5)
        val id = names.track(listOf("thing"), listOf(laptop))[0]
        names.vote(id, "my new laptop")
        names.track(emptyList(), emptyList())
        val again = names.track(listOf("thing"), listOf(laptopMoved))[0]
        assertEquals(id, again)
        assertEquals("my new laptop", names.nameOf(again))
    }
}
