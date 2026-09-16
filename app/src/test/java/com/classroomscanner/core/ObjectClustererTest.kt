package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectClustererTest {
    private fun det(label: String, angle: Float, color: String? = null) = FrameDetection(label, angle, color)

    @Test
    fun sameObjectOverManyFramesCountsOnce() {
        val c = ObjectClusterer()
        repeat(100) { c.addFrame(listOf(det("chair", 10f + (it % 5)))) }
        assertEquals(1, c.confirmed().size)
        assertEquals(1, c.confirmed()[0].count)
    }

    @Test
    fun threeInOneFrameCountsThree() {
        val c = ObjectClusterer()
        repeat(5) { c.addFrame(listOf(det("chair", 0f), det("chair", 5f), det("chair", 10f))) }
        assertEquals(1, c.confirmed().size)
        assertEquals(3, c.confirmed()[0].count)
    }

    @Test
    fun sameLabelFarApartMakesTwoClusters() {
        val c = ObjectClusterer()
        repeat(3) { c.addFrame(listOf(det("chair", 0f))) }
        repeat(3) { c.addFrame(listOf(det("chair", 90f))) }
        assertEquals(2, c.confirmed().size)
    }

    @Test
    fun differentLabelsDoNotMerge() {
        val c = ObjectClusterer()
        repeat(3) { c.addFrame(listOf(det("chair", 0f), det("laptop", 2f))) }
        assertEquals(setOf("chair", "laptop"), c.confirmed().map { it.label }.toSet())
    }

    @Test
    fun wrapAroundMergesIntoOneCluster() {
        val c = ObjectClusterer()
        repeat(3) {
            c.addFrame(listOf(det("tv", 355f)))
            c.addFrame(listOf(det("tv", 5f)))
        }
        assertEquals(1, c.clusters().size)
    }

    @Test
    fun needsThreeFramesAndReportsConfirmationOnce() {
        val c = ObjectClusterer()
        val frame = listOf(det("chair", 0f))
        assertTrue(c.addFrame(frame).isEmpty())
        assertTrue(c.addFrame(frame).isEmpty())
        assertTrue(c.confirmed().isEmpty())
        assertEquals(1, c.addFrame(frame).size)
        assertEquals(1, c.confirmed().size)
        assertTrue(c.addFrame(frame).isEmpty())
    }

    @Test
    fun colorIsMajorityVoteIgnoringNull() {
        val c = ObjectClusterer()
        c.addFrame(listOf(det("chair", 0f, "blue")))
        c.addFrame(listOf(det("chair", 0f, "gray")))
        c.addFrame(listOf(det("chair", 0f, "blue")))
        c.addFrame(listOf(det("chair", 0f, null)))
        assertEquals("blue", c.confirmed()[0].color)
    }

    @Test
    fun colorIsNullWhenNeverNamed() {
        val c = ObjectClusterer()
        repeat(3) { c.addFrame(listOf(det("chair", 0f))) }
        assertNull(c.confirmed()[0].color)
    }

    @Test
    fun meanAngleFollowsSamplesAcrossZero() {
        val c = ObjectClusterer()
        c.addFrame(listOf(det("chair", 355f)))
        c.addFrame(listOf(det("chair", 5f)))
        assertEquals(0f, c.clusters()[0].meanAngle, 0.01f)
    }

    @Test
    fun singleColorVoteIsNotTrusted() {
        val c = ObjectClusterer()
        c.addFrame(listOf(det("chair", 0f, "blue")))
        c.addFrame(listOf(det("chair", 0f, null)))
        c.addFrame(listOf(det("chair", 0f, null)))
        assertNull(c.confirmed()[0].color)
    }

    @Test
    fun colorWithoutClearMajorityIsNotTrusted() {
        val c = ObjectClusterer()
        listOf("blue", "red", "blue", "red", "gray").forEach { c.addFrame(listOf(det("chair", 0f, it))) }
        assertNull(c.confirmed()[0].color)
    }
}
