package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryBuilderTest {
    private fun obj(label: String, count: Int, color: String?, angle: Float) = ObjectSummary(label, count, color, angle)

    @Test fun singularWithColor() = assertEquals("a blue chair", SummaryBuilder.describe(obj("chair", 1, "blue", 0f)))
    @Test fun anBeforeVowelColor() = assertEquals("an orange cup", SummaryBuilder.describe(obj("cup", 1, "orange", 0f)))
    @Test fun anBeforeVowelLabel() = assertEquals("an umbrella", SummaryBuilder.describe(obj("umbrella", 1, null, 0f)))
    @Test fun pluralWithColor() = assertEquals("3 blue chairs", SummaryBuilder.describe(obj("chair", 3, "blue", 0f)))
    @Test fun pluralWithoutColor() = assertEquals("2 books", SummaryBuilder.describe(obj("book", 2, null, 0f)))

    @Test
    fun pluralRules() {
        assertEquals("people", SummaryBuilder.plural("person"))
        assertEquals("mice", SummaryBuilder.plural("mouse"))
        assertEquals("knives", SummaryBuilder.plural("knife"))
        assertEquals("couches", SummaryBuilder.plural("couch"))
        assertEquals("buses", SummaryBuilder.plural("bus"))
        assertEquals("wine glasses", SummaryBuilder.plural("wine glass"))
        assertEquals("cell phones", SummaryBuilder.plural("cell phone"))
        assertEquals("skis", SummaryBuilder.plural("skis"))
        assertEquals("tvs", SummaryBuilder.plural("tv"))
    }

    @Test
    fun fullSummaryGroupsBySectorInOrder() {
        val text = SummaryBuilder.fullSummary(
            listOf(
                obj("laptop", 1, "black", 90f),
                obj("chair", 3, "blue", 10f),
                obj("tv", 1, "white", 180f),
                obj("book", 2, null, 270f),
            ),
            100,
        )
        assertEquals(
            "Around you: 3 blue chairs in front; a black laptop on your right; a white tv behind you; 2 books on your left.",
            text,
        )
    }

    @Test
    fun sameLabelAndColorInOneSectorAreMerged() {
        val text = SummaryBuilder.fullSummary(
            listOf(obj("chair", 2, "blue", 0f), obj("chair", 1, "blue", 30f)),
            100,
        )
        assertEquals("Around you: 3 blue chairs in front.", text)
    }

    @Test
    fun biggerGroupsComeFirstWithinASector() {
        val text = SummaryBuilder.fullSummary(
            listOf(obj("laptop", 1, "black", 0f), obj("chair", 4, null, 10f)),
            100,
        )
        assertEquals("Around you: 4 chairs, a black laptop in front.", text)
    }

    @Test
    fun partialCoverageAddsPrefix() {
        val text = SummaryBuilder.fullSummary(listOf(obj("chair", 1, null, 0f)), 70)
        assertEquals("I scanned 70 percent of the room. Around you: a chair in front.", text)
    }

    @Test
    fun emptyResult() {
        assertEquals(
            "No objects found. Try better lighting and turn slowly.",
            SummaryBuilder.fullSummary(emptyList(), 100),
        )
    }

    @Test
    fun livePhrases() {
        assertEquals("Blue chair on your left.", SummaryBuilder.livePhrase(obj("chair", 1, "blue", 270f)))
        assertEquals("Laptop in front.", SummaryBuilder.livePhrase(obj("laptop", 1, null, 0f)))
    }

    @Test
    fun sameLabelWithDifferentColorsIsOneGroupWithColorList() {
        val text = SummaryBuilder.fullSummary(
            listOf(
                obj("chair", 2, "blue", 80f),
                obj("chair", 1, "red", 100f),
                obj("chair", 1, "gray", 120f),
            ),
            100,
        )
        assertEquals("Around you: 4 chairs in blue, red and gray on your right.", text)
    }

    @Test
    fun twoColorsAreJoinedWithAnd() {
        val text = SummaryBuilder.fullSummary(
            listOf(obj("chair", 1, "red", 0f), obj("chair", 1, "blue", 10f)),
            100,
        )
        assertEquals("Around you: 2 chairs in red and blue in front.", text)
    }

    @Test
    fun unknownColorsAreLeftOutOfTheList() {
        val text = SummaryBuilder.fullSummary(
            listOf(obj("bottle", 2, "green", 0f), obj("bottle", 1, null, 5f)),
            100,
        )
        assertEquals("Around you: 3 bottles in green in front.", text)
    }

    @Test
    fun groupWithNoKnownColorHasNoColor() {
        val text = SummaryBuilder.fullSummary(
            listOf(obj("book", 1, null, 0f), obj("book", 2, null, 20f)),
            100,
        )
        assertEquals("Around you: 3 books in front.", text)
    }

    @Test
    fun mixedGroupsInOneSector() {
        val text = SummaryBuilder.fullSummary(
            listOf(
                obj("chair", 2, "blue", 0f),
                obj("chair", 1, "red", 5f),
                obj("laptop", 1, "black", 10f),
            ),
            100,
        )
        assertEquals("Around you: 3 chairs in blue and red, a black laptop in front.", text)
    }

    @Test
    fun peopleAreNeverGivenAColor() {
        val text = SummaryBuilder.fullSummary(
            listOf(obj("person", 1, "blue", 180f), obj("person", 1, "red", 190f)),
            100,
        )
        assertEquals("Around you: 2 people behind you.", text)
        assertEquals("Person on your left.", SummaryBuilder.livePhrase(obj("person", 1, "blue", 270f)))
        assertEquals("a person", SummaryBuilder.describe(obj("person", 1, "blue", 0f)))
    }
}
