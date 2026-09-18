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
        assertEquals(VoiceCommand.Search("my laptop"), VoiceCommandParser.parse("search for my laptop"))
    }

    @Test
    fun screenCommands() {
        assertEquals(VoiceCommand.FullScan, VoiceCommandParser.parse("Full scan"))
        assertEquals(VoiceCommand.FullScan, VoiceCommandParser.parse("scan the room"))
        assertEquals(VoiceCommand.LiveScan, VoiceCommandParser.parse("start live scan"))
        assertEquals(VoiceCommand.History, VoiceCommandParser.parse("open history"))
        assertEquals(VoiceCommand.Saved, VoiceCommandParser.parse("show saved"))
        assertEquals(VoiceCommand.Search(""), VoiceCommandParser.parse("open search"))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("go home"))
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("go back"))
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("back"))
    }

    @Test
    fun addCommands() {
        assertEquals(VoiceCommand.AddPerson, VoiceCommandParser.parse("add person"))
        assertEquals(VoiceCommand.AddPerson, VoiceCommandParser.parse("add a new person"))
        assertEquals(VoiceCommand.AddCar, VoiceCommandParser.parse("add car"))
        assertEquals(VoiceCommand.AddObject, VoiceCommandParser.parse("add object"))
        assertEquals(VoiceCommand.AddObject, VoiceCommandParser.parse("add an item"))
    }

    @Test
    fun scanControlCommands() {
        assertEquals(VoiceCommand.Start, VoiceCommandParser.parse("start"))
        assertEquals(VoiceCommand.Start, VoiceCommandParser.parse("begin scanning"))
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("stop"))
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("finish"))
        assertEquals(VoiceCommand.SwitchCamera, VoiceCommandParser.parse("switch camera"))
        assertEquals(VoiceCommand.SwitchCamera, VoiceCommandParser.parse("change the camera"))
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("repeat"))
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("say that again"))
        assertEquals(VoiceCommand.ReadText, VoiceCommandParser.parse("view text"))
        assertEquals(VoiceCommand.ReadText, VoiceCommandParser.parse("read the text"))
    }

    @Test
    fun identifyCommands() {
        assertEquals(VoiceCommand.IdentifyPerson, VoiceCommandParser.parse("Who is this?"))
        assertEquals(VoiceCommand.IdentifyPerson, VoiceCommandParser.parse("who's that"))
        assertEquals(VoiceCommand.IdentifyThing, VoiceCommandParser.parse("What is this?"))
        assertEquals(VoiceCommand.IdentifyThing, VoiceCommandParser.parse("what's that"))
    }

    @Test
    fun microphoneOff() {
        assertEquals(VoiceCommand.StopListening, VoiceCommandParser.parse("stop listening"))
        assertEquals(VoiceCommand.StopListening, VoiceCommandParser.parse("microphone off"))
    }

    @Test
    fun walkCommands() {
        assertEquals(VoiceCommand.Walk, VoiceCommandParser.parse("walk"))
        assertEquals(VoiceCommand.Walk, VoiceCommandParser.parse("start walking"))
        assertEquals(VoiceCommand.SavePlace("home"), VoiceCommandParser.parse("save this place as home"))
        assertEquals(VoiceCommand.SavePlace("the shop"), VoiceCommandParser.parse("save place the shop"))
        assertEquals(VoiceCommand.GoTo("home"), VoiceCommandParser.parse("take me to home"))
        assertEquals(VoiceCommand.GoTo("the shop"), VoiceCommandParser.parse("guide me to the shop"))
    }

    @Test
    fun searchWithoutQueryAndNoiseAreUnknown() {
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse("find"))
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse("hello there"))
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse(""))
    }
}
