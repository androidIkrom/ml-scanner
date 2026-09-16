package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionTest {
    private val blueChair = listOf(FrameDetection("chair", 0f, "blue"))

    private fun turnFullCircle(s: ScanSession) {
        var a = 0f
        while (a < 360f) {
            s.onHeading(a)
            a += 5f
        }
        s.onHeading(0f)
    }

    @Test
    fun liveModeSpeaksWhenObjectConfirmed() {
        val s = ScanSession(ScanMode.LIVE, 0L)
        assertTrue(s.onFrame(blueChair).isEmpty())
        assertTrue(s.onFrame(blueChair).isEmpty())
        assertEquals(listOf("Blue chair in front."), s.onFrame(blueChair))
        assertTrue(s.onFrame(blueChair).isEmpty())
    }

    @Test
    fun fullModeNeverSpeaksPerObject() {
        val s = ScanSession(ScanMode.FULL, 0L)
        repeat(5) { assertTrue(s.onFrame(blueChair).isEmpty()) }
    }

    @Test
    fun fullModeStopsWhenCircleCovered() {
        val s = ScanSession(ScanMode.FULL, 1_000L)
        assertFalse(s.shouldAutoStop(1_000L))
        turnFullCircle(s)
        assertEquals(100, s.coveragePercent())
        assertTrue(s.shouldAutoStop(2_000L))
    }

    @Test
    fun fullModeStopsAfterTimeout() {
        val s = ScanSession(ScanMode.FULL, 1_000L, timeoutMs = 60_000L)
        assertFalse(s.shouldAutoStop(60_999L))
        assertTrue(s.shouldAutoStop(61_000L))
    }

    @Test
    fun liveModeNeverStopsItself() {
        val s = ScanSession(ScanMode.LIVE, 0L)
        turnFullCircle(s)
        assertFalse(s.shouldAutoStop(10_000_000L))
    }

    @Test
    fun confirmedCountSumsClusterCounts() {
        val s = ScanSession(ScanMode.FULL, 0L)
        repeat(3) {
            s.onFrame(listOf(FrameDetection("chair", 0f, null), FrameDetection("chair", 5f, null), FrameDetection("tv", 180f, null)))
        }
        assertEquals(3, s.confirmedCount())
    }

    @Test
    fun finishBuildsResultAndIgnoresLaterFrames() {
        val s = ScanSession(ScanMode.FULL, 5L)
        repeat(3) { s.onFrame(blueChair) }
        s.onHeading(0f)
        val r = s.finish()
        assertTrue(s.finished)
        assertEquals(ScanMode.FULL, r.mode)
        assertEquals(5L, r.startedAt)
        assertEquals(2, r.coveragePercent)
        assertEquals(listOf("chair"), r.objects.map { it.label })
        assertEquals("I scanned 2 percent of the room. Around you: a blue chair in front.", r.summaryText)
        assertTrue(s.onFrame(blueChair).isEmpty())
        assertFalse(s.shouldAutoStop(Long.MAX_VALUE))
    }
}
