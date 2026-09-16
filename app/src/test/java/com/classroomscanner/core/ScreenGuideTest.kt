package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenGuideTest {
    private val w = 900f
    private val h = 1800f

    @Test
    fun positionsUseAThreeByThreeGrid() {
        assertEquals("top left", ScreenGuide.position(50f, 50f, w, h))
        assertEquals("top", ScreenGuide.position(450f, 50f, w, h))
        assertEquals("top right", ScreenGuide.position(850f, 50f, w, h))
        assertEquals("left", ScreenGuide.position(50f, 900f, w, h))
        assertEquals("center", ScreenGuide.position(450f, 900f, w, h))
        assertEquals("right", ScreenGuide.position(850f, 900f, w, h))
        assertEquals("bottom left", ScreenGuide.position(50f, 1750f, w, h))
        assertEquals("bottom", ScreenGuide.position(450f, 1750f, w, h))
        assertEquals("bottom right", ScreenGuide.position(899f, 1799f, w, h))
    }

    @Test
    fun edgesAndEmptyScreensStayInRange() {
        assertEquals("bottom right", ScreenGuide.position(900f, 1800f, w, h))
        assertEquals("top left", ScreenGuide.position(-5f, -5f, w, h))
        assertEquals("center", ScreenGuide.position(10f, 10f, 0f, 0f))
    }

    @Test
    fun controlDescriptions() {
        assertEquals("Start button", ScreenGuide.describeControl("Start", ControlKind.BUTTON))
        assertEquals("Speak results switch, on", ScreenGuide.describeControl("Speak results", ControlKind.SWITCH, "on"))
        assertEquals("Front, selected", ScreenGuide.describeControl("Front", ControlKind.TOGGLE, "selected"))
        assertEquals("Confidence slider, 0.5", ScreenGuide.describeControl("Confidence", ControlKind.SLIDER, "0.5"))
        assertEquals("Full Scan", ScreenGuide.describeControl("Full Scan", ControlKind.OTHER))
        assertEquals("Full Scan, unavailable", ScreenGuide.describeControl("Full Scan", ControlKind.OTHER, enabled = false))
    }

    @Test
    fun navigateUpIsCalledBack() {
        assertEquals("Back button", ScreenGuide.describeControl("Navigate up", ControlKind.BUTTON))
    }

    @Test
    fun summaryListsItemsTopToBottomThenLeftToRight() {
        val items = listOf(
            GuideItem("History", 450f, 1500f),
            GuideItem("Voice guide, on", 850f, 60f),
            GuideItem("Back button", 50f, 60f),
            GuideItem("Full Scan", 450f, 700f),
        )
        assertEquals(
            "This screen has: Back button, top left. Voice guide, on, top right. Full Scan, center. History, bottom.",
            ScreenGuide.summary(items, w, h),
        )
    }

    @Test
    fun emptySummary() {
        assertEquals("Nothing to press on this screen.", ScreenGuide.summary(emptyList(), w, h))
    }

    @Test
    fun longListsAreShortened() {
        val items = (0 until 15).map { GuideItem("Play $it", 450f, 100f + it * 100f) }
        val text = ScreenGuide.summary(items, w, 3000f)
        assertEquals(true, text.endsWith("Play 11, center. And 3 more."))
    }
}
