package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResolverTest {
    private val saved = listOf(
        SavedName(SavedKind.PERSON, 1, "Ali", null),
        SavedName(SavedKind.OBJECT, 2, "My Bag", "backpack"),
        SavedName(SavedKind.CAR, 3, "Dad's Car", "car"),
        SavedName(SavedKind.PERSON, 4, "Madina", null),
    )

    @Test
    fun cleanDropsCommandWordsAndPunctuation() {
        assertEquals("keys", SearchResolver.clean("Where is my keys?"))
        assertEquals("cup", SearchResolver.clean("find the cup"))
        assertEquals("cell phone", SearchResolver.clean("Look for a Cell-Phone!"))
    }

    @Test
    fun savedPersonByExactName() {
        assertEquals(SearchTarget.Person(1, "Ali"), SearchResolver.resolve("find Ali", saved))
    }

    @Test
    fun savedItemIgnoresMy() {
        assertEquals(SearchTarget.Item(2, "My Bag", "backpack"), SearchResolver.resolve("where is my bag", saved))
    }

    @Test
    fun savedNameContainedInQuery() {
        assertEquals(SearchTarget.Item(3, "Dad's Car", "car"), SearchResolver.resolve("find dads car please", saved))
    }

    @Test
    fun savedNameWithSmallTypo() {
        assertEquals(SearchTarget.Person(4, "Madina"), SearchResolver.resolve("find medina", saved))
    }

    @Test
    fun cocoLabelDirect() {
        assertEquals(SearchTarget.Label("cup"), SearchResolver.resolve("find a cup", saved))
    }

    @Test
    fun cocoLabelPluralAndSynonym() {
        assertEquals(SearchTarget.Label("chair"), SearchResolver.resolve("chairs", saved))
        assertEquals(SearchTarget.Label("cell phone"), SearchResolver.resolve("where is the phone", saved))
        assertEquals(SearchTarget.Label("dining table"), SearchResolver.resolve("find desks", saved))
        assertEquals(SearchTarget.Label("person"), SearchResolver.resolve("people", saved))
        assertEquals(SearchTarget.Label("cell phone"), SearchResolver.resolve("cell phones", emptyList()))
    }

    @Test
    fun unknownKeepsCleanedText() {
        assertEquals(SearchTarget.Unknown("keys"), SearchResolver.resolve("where are my keys", saved))
        assertEquals(SearchTarget.Unknown(""), SearchResolver.resolve("find", saved))
    }

    @Test
    fun cocoHasEightyLabels() {
        assertEquals(80, CocoLabels.ALL.size)
    }
}
