package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun searchCommandsKeepTheQuery() {
        assertEquals(VoiceCommand.Search("my bag"), VoiceCommandParser.parse("Find my bag"))
        assertEquals(VoiceCommand.Search("a chair"), VoiceCommandParser.parse("search for a chair"))
        assertEquals(VoiceCommand.Search("ali"), VoiceCommandParser.parse("Where's Ali?"))
        assertEquals(VoiceCommand.Search("the cup"), VoiceCommandParser.parse("where is the cup"))
        assertEquals(VoiceCommand.Search("keys"), VoiceCommandParser.parse("look for keys"))
    }

    @Test
    fun screenCommands() {
        assertEquals(VoiceCommand.FullScan, VoiceCommandParser.parse("Full scan"))
        assertEquals(VoiceCommand.FullScan, VoiceCommandParser.parse("scan the room"))
        assertEquals(VoiceCommand.LiveScan, VoiceCommandParser.parse("start live scan"))
        assertEquals(VoiceCommand.History, VoiceCommandParser.parse("open history"))
        assertEquals(VoiceCommand.Saved, VoiceCommandParser.parse("show saved"))
    }

    @Test
    fun searchWithoutQueryAndNoiseAreUnknown() {
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse("find"))
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse("hello there"))
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse(""))
    }
}
