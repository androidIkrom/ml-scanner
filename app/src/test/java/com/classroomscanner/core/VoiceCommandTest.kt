package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandParserTest {

    private fun assertAll(expected: VoiceCommand, vararg phrases: String) {
        phrases.forEach { assertEquals(it, expected, VoiceCommandParser.parse(it)) }
    }

    @Test
    fun searchCommandsKeepTheQuery() {
        assertEquals(VoiceCommand.Search("my bag"), VoiceCommandParser.parse("Find my bag"))
        assertEquals(VoiceCommand.Search("a chair"), VoiceCommandParser.parse("search for a chair"))
        assertEquals(VoiceCommand.Search("ali"), VoiceCommandParser.parse("Where's Ali?"))
        assertEquals(VoiceCommand.Search("the cup"), VoiceCommandParser.parse("where is the cup"))
        assertEquals(VoiceCommand.Search("keys"), VoiceCommandParser.parse("look for keys"))
        assertEquals(VoiceCommand.Search("my laptop"), VoiceCommandParser.parse("search for my laptop"))
        assertEquals(VoiceCommand.Search("my phone"), VoiceCommandParser.parse("search my phone"))
        assertEquals(VoiceCommand.Search("my phone"), VoiceCommandParser.parse("can you find my phone"))
    }

    @Test
    fun openingScreens() {
        assertAll(
            VoiceCommand.FullScan,
            "full scan", "open full scan", "full scan open", "start full scan", "begin full scan",
            "scan the room", "do a full scan",
        )
        assertAll(
            VoiceCommand.LiveScan,
            "live scan", "open live scan", "live scan open", "begin live scan", "start live scanning",
        )
        assertAll(VoiceCommand.History, "history", "open history", "history open", "show history", "past scans")
        assertAll(VoiceCommand.Saved, "saved", "open saved", "saved open", "show me saved", "open people", "my things")
        assertAll(VoiceCommand.Walk, "walk", "open walk", "walk mode", "start walking", "begin walk mode")
        assertAll(VoiceCommand.Search(""), "open search", "search open", "show search", "search screen")
        assertAll(VoiceCommand.Home, "home", "go home", "main menu", "open home")
        assertAll(VoiceCommand.Back, "back", "go back", "previous screen", "close this", "exit", "leave")
    }

    @Test
    fun addingSavedThings() {
        assertAll(
            VoiceCommand.AddObject,
            "add object", "add an item", "create object", "create a new object", "new thing", "save an object",
        )
        assertAll(VoiceCommand.AddCar, "add car", "create car", "new car", "save my car")
        assertAll(
            VoiceCommand.AddPerson,
            "add person", "add a new person", "create person", "new face", "save a person",
        )
    }

    @Test
    fun runningThingsOnTheScreen() {
        assertAll(VoiceCommand.Start, "start", "begin scanning", "enter", "ok", "confirm", "do it", "press the button")
        assertAll(VoiceCommand.Stop, "stop", "finish", "end scan", "stop scanning", "cancel")
        assertAll(
            VoiceCommand.SwitchCamera,
            "switch camera", "change the camera", "front camera", "back camera", "flip camera", "other camera",
        )
        assertAll(VoiceCommand.Repeat, "repeat", "say that again", "one more time", "what did you say")
        assertAll(VoiceCommand.ReadText, "view text", "read the text", "show the log", "read it out")
        assertAll(VoiceCommand.Delete, "delete this", "remove this", "delete it")
    }

    @Test
    fun identifyCommands() {
        assertAll(VoiceCommand.IdentifyPerson, "Who is this?", "who's that", "who is in front of me")
        assertAll(
            VoiceCommand.IdentifyThing,
            "What is this?", "what's that", "identify this", "what am i looking at",
        )
    }

    @Test
    fun learnerModeInManyShapes() {
        assertAll(
            VoiceCommand.Learner(true),
            "learner mode on", "learning mode on", "turn on learning mode", "learning on", "start learner mode",
        )
        assertAll(
            VoiceCommand.Learner(false),
            "learner mode off", "learning mode off", "turn off learning mode", "learning off",
            "stop learner mode", "disable learning mode",
        )
    }

    @Test
    fun helpAndPlaces() {
        assertEquals(VoiceCommand.Help(ScreenHelp.SEARCH), VoiceCommandParser.parse("What is search?"))
        assertEquals(VoiceCommand.Help(ScreenHelp.SAVED), VoiceCommandParser.parse("what does saved do"))
        assertEquals(VoiceCommand.Help(""), VoiceCommandParser.parse("help"))
        assertEquals(VoiceCommand.Help(""), VoiceCommandParser.parse("what is on this screen"))
        assertEquals(VoiceCommand.SavePlace("home"), VoiceCommandParser.parse("save this place as home"))
        assertEquals(VoiceCommand.GoTo("home"), VoiceCommandParser.parse("take me to home"))
        assertEquals(VoiceCommand.GoTo("the shop"), VoiceCommandParser.parse("guide me to the shop"))
    }

    @Test
    fun microphoneOff() {
        assertAll(
            VoiceCommand.StopListening,
            "stop listening", "microphone off", "mic off", "voice off", "turn off the microphone",
        )
    }

    @Test
    fun noiseIsUnknown() {
        assertAll(VoiceCommand.Unknown, "find", "hello there", "", "blue sky and green grass")
    }
}
