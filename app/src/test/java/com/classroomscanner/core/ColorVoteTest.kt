package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorVoteTest {
    private val eps = 0.01f

    @Test
    fun rgbToHsvConvertsPrimaries() {
        val red = ColorVote.rgbToHsv(0xFF0000)
        assertEquals(0f, red[0], eps)
        assertEquals(1f, red[1], eps)
        assertEquals(1f, red[2], eps)
        assertEquals(120f, ColorVote.rgbToHsv(0x00FF00)[0], eps)
        assertEquals(240f, ColorVote.rgbToHsv(0x0000FF)[0], eps)
        val gray = ColorVote.rgbToHsv(0x808080)
        assertEquals(0f, gray[1], eps)
        assertEquals(0.502f, gray[2], eps)
    }

    @Test
    fun emptyVotesGiveNull() {
        assertNull(ColorVote.pick(emptyList()))
        assertNull(ColorVote.pick(listOf(null, null)))
    }

    @Test
    fun enoughColorfulPixelsBeatShadows() {
        // 6 gray/black shadow pixels, 4 blue pixels: blue share 40% >= 30%.
        val names = List(4) { "gray" } + List(2) { "black" } + List(4) { "blue" }
        assertEquals("blue", ColorVote.pick(names))
    }

    @Test
    fun fewColorfulPixelsLeaveItNeutral() {
        val names = List(8) { "white" } + List(2) { "red" }
        assertEquals("white", ColorVote.pick(names))
    }

    @Test
    fun topChromaticWins() {
        val names = List(3) { "red" } + List(5) { "blue" } + List(2) { "gray" }
        assertEquals("blue", ColorVote.pick(names))
    }

    @Test
    fun nameOfPixelsUsesWhiteBalance() {
        // A gray object under a warm lamp looks orange-brown without correction.
        val warmGray = 0x96785A
        val pixels = IntArray(10) { warmGray }
        assertEquals("brown", ColorVote.nameOfPixels(pixels, WhiteBalance.Gains(1f, 1f, 1f)))
        assertEquals("gray", ColorVote.nameOfPixels(pixels, WhiteBalance.gains(150f, 120f, 90f)))
    }

    @Test
    fun nameOfPixelsFindsBlueObjectWithShadows() {
        val pixels = IntArray(10) { i -> if (i < 5) 0x1E5AC8 else 0x202020 }
        assertEquals("blue", ColorVote.nameOfPixels(pixels, WhiteBalance.Gains(1f, 1f, 1f)))
    }

    @Test
    fun peopleHaveNoColor() {
        assertEquals(false, ColorPolicy.hasColor("person"))
        assertEquals(true, ColorPolicy.hasColor("chair"))
    }
}
