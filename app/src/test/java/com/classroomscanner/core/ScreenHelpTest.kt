package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenHelpTest {
    @Test
    fun everyScreenHasAShortDescription() {
        for (key in ScreenHelp.KEYS) {
            val text = ScreenHelp.describe(key)
            assertNotNull("no help for $key", text)
            assertTrue("help for $key is too long: $text", text!!.length <= 220)
        }
    }

    @Test
    fun unknownKeyHasNoHelp() {
        assertNull(ScreenHelp.describe("nothing"))
    }

    @Test
    fun questionsFindTheirTopic() {
        assertEquals(ScreenHelp.SEARCH, ScreenHelp.topicOf("what is search"))
        assertEquals(ScreenHelp.SAVED, ScreenHelp.topicOf("what does saved do"))
        assertEquals(ScreenHelp.HISTORY, ScreenHelp.topicOf("what is the history"))
        assertEquals(ScreenHelp.FULL_SCAN, ScreenHelp.topicOf("what is full scan"))
        assertEquals(ScreenHelp.LIVE_SCAN, ScreenHelp.topicOf("what does live scan do"))
        assertEquals(ScreenHelp.LEARNER, ScreenHelp.topicOf("what is learner mode"))
        assertEquals(ScreenHelp.APP, ScreenHelp.topicOf("what is this app"))
    }

    @Test
    fun questionsWithoutATopic() {
        assertNull(ScreenHelp.topicOf("what is the weather"))
        assertNull(ScreenHelp.topicOf(""))
    }
}

class VoiceHelpCommandTest {
    @Test
    fun askingAboutAScreen() {
        assertEquals(VoiceCommand.Help(ScreenHelp.SEARCH), VoiceCommandParser.parse("What is search?"))
        assertEquals(VoiceCommand.Help(ScreenHelp.SAVED), VoiceCommandParser.parse("what does saved do"))
        assertEquals(VoiceCommand.Help(""), VoiceCommandParser.parse("help"))
        assertEquals(VoiceCommand.Help(""), VoiceCommandParser.parse("what is on this screen"))
    }

    @Test
    fun identifyStillWins() {
        assertEquals(VoiceCommand.IdentifyThing, VoiceCommandParser.parse("what is this"))
        assertEquals(VoiceCommand.IdentifyPerson, VoiceCommandParser.parse("who is this"))
    }

    @Test
    fun learnerMode() {
        assertEquals(VoiceCommand.Learner(true), VoiceCommandParser.parse("learner mode on"))
        assertEquals(VoiceCommand.Learner(false), VoiceCommandParser.parse("turn off learner mode"))
    }
}
