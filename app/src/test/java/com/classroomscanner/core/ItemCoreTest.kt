package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemKindTest {
    @Test
    fun carsAllowOnlyVehicles() {
        assertTrue(ItemKind.CAR.allows("car"))
        assertTrue(ItemKind.CAR.allows("truck"))
        assertTrue(ItemKind.CAR.allows("bus"))
        assertTrue(ItemKind.CAR.allows("motorcycle"))
        assertFalse(ItemKind.CAR.allows("backpack"))
        assertFalse(ItemKind.CAR.allows("person"))
    }

    @Test
    fun objectsAllowAnythingButPeople() {
        assertTrue(ItemKind.OBJECT.allows("backpack"))
        assertTrue(ItemKind.OBJECT.allows("car"))
        assertFalse(ItemKind.OBJECT.allows("person"))
    }
}

class ItemMatcherTest {
    private val bag = KnownItem(1, "my bag", "backpack", floatArrayOf(1f, 0f))
    private val cup = KnownItem(2, "my cup", "cup", floatArrayOf(1f, 0f))
    private val otherBag = KnownItem(3, "red bag", "backpack", floatArrayOf(0f, 1f))

    @Test
    fun matchesOnlyItemsWithTheSameLabel() {
        val match = ItemMatcher.bestMatch(floatArrayOf(1f, 0f), "cup", listOf(bag, cup))
        assertEquals(2L, match!!.itemId)
    }

    @Test
    fun picksHighestSimilarity() {
        val match = ItemMatcher.bestMatch(floatArrayOf(0.1f, 1f), "backpack", listOf(bag, otherBag))
        assertEquals("red bag", match!!.name)
    }

    @Test
    fun belowThresholdIsNull() {
        assertNull(ItemMatcher.bestMatch(floatArrayOf(1f, 1f), "backpack", listOf(otherBag), threshold = 0.9f))
        assertNull(ItemMatcher.bestMatch(floatArrayOf(1f, 0f), "backpack", emptyList()))
    }
}

class ItemEnrollmentGuideTest {
    @Test
    fun walksThroughThreeSteps() {
        val guide = ItemEnrollmentGuide(samplesPerStep = 2)
        assertEquals(6, guide.total)
        assertEquals(ItemStep.STILL, guide.currentStep)
        guide.offer()
        assertFalse(guide.stepFinished)
        assertEquals(16, guide.percent())
        guide.offer()
        assertTrue(guide.stepFinished)
        assertEquals(ItemStep.LEFT, guide.currentStep)
        repeat(4) { guide.offer() }
        assertTrue(guide.done)
        assertNull(guide.currentStep)
        assertEquals(100, guide.percent())
    }

    @Test
    fun offerAfterDoneIsIgnored() {
        val guide = ItemEnrollmentGuide(samplesPerStep = 1)
        repeat(3) { guide.offer() }
        assertFalse(guide.offer())
        assertEquals(3, guide.captured)
    }
}

class CenterPickTest {
    @Test
    fun picksClosestToCenterAmongLargeEnough() {
        val candidates = listOf(
            Candidate(0.5f, 0.5f, 0.01f), // centered but too small
            Candidate(0.2f, 0.5f, 0.2f),
            Candidate(0.6f, 0.45f, 0.1f),
        )
        assertEquals(2, CenterPick.pick(candidates, minArea = 0.05f))
    }

    @Test
    fun nullWhenNothingQualifies() {
        assertNull(CenterPick.pick(listOf(Candidate(0.5f, 0.5f, 0.01f)), minArea = 0.05f))
        assertNull(CenterPick.pick(emptyList(), minArea = 0f))
    }
}
