package com.classroomscanner.core

sealed interface VoiceCommand {
    /** Screens. */
    data object FullScan : VoiceCommand
    data object LiveScan : VoiceCommand
    data object History : VoiceCommand
    data object Saved : VoiceCommand
    data object Home : VoiceCommand
    data object Back : VoiceCommand

    /** Search; an empty query only opens the search screen. */
    data class Search(val query: String) : VoiceCommand

    /** Adding saved things. */
    data object AddPerson : VoiceCommand
    data object AddCar : VoiceCommand
    data object AddObject : VoiceCommand

    /** Running a scan or search. */
    data object Start : VoiceCommand
    data object Stop : VoiceCommand
    data object SwitchCamera : VoiceCommand
    data object Repeat : VoiceCommand
    data object ReadText : VoiceCommand

    /** "Who is this" and "what is this" during a scan. */
    data object IdentifyPerson : VoiceCommand
    data object IdentifyThing : VoiceCommand

    /** Switches the microphone off. */
    data object StopListening : VoiceCommand

    /** Explains a [ScreenHelp] topic; an empty topic means the screen the user is on. */
    data class Help(val topic: String) : VoiceCommand

    /** Turns the spoken screen introductions on or off. */
    data class Learner(val on: Boolean) : VoiceCommand

    data object Unknown : VoiceCommand
}

/** Maps spoken commands to actions. English only; the app's speech is English. */
object VoiceCommandParser {
    private val search = Regex("^(search for|search|find|where is|where are|wheres|look for|locate)\\s+(.+)$")

    fun parse(text: String): VoiceCommand {
        val t = text.lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.isEmpty()) return VoiceCommand.Unknown
        val words = t.split(' ').toSet()

        // Checked before "stop", which would otherwise end the scan instead of the microphone.
        if (t.contains("stop listening") || t.contains("microphone off") || t.contains("mic off") ||
            t.contains("voice off")
        ) {
            return VoiceCommand.StopListening
        }
        if (t.contains("learner")) {
            return VoiceCommand.Learner(!t.contains(" off") && !t.contains("stop"))
        }
        val aboutHere = t.contains("this screen") || Regex("\\bhere\\b").containsMatchIn(t)
        if (t.startsWith("who is") || t.startsWith("whos")) return VoiceCommand.IdentifyPerson
        if ((t.startsWith("what is") || t.startsWith("whats")) && !aboutHere) {
            val rest = t.removePrefix("what is").removePrefix("whats").trim()
            if (rest == "this" || rest == "that" || rest.isEmpty()) return VoiceCommand.IdentifyThing
            ScreenHelp.topicOf(rest)?.let { return VoiceCommand.Help(it) }
            return VoiceCommand.IdentifyThing
        }
        if (t.startsWith("what does") || t.startsWith("what do") || t.startsWith("tell me about") ||
            t.startsWith("explain") || t.startsWith("how do i use")
        ) {
            ScreenHelp.topicOf(t)?.let { return VoiceCommand.Help(it) }
            return VoiceCommand.Help("")
        }
        if (t == "help" || t.startsWith("help me") || aboutHere) return VoiceCommand.Help("")
        if ("search" in words && ("open" in words || "start" in words) && !t.contains("search for")) {
            return VoiceCommand.Search("")
        }
        search.find(t)?.let { return VoiceCommand.Search(it.groupValues[2]) }

        if ("add" in words || "new" in words) {
            when {
                "person" in words || "people" in words || "face" in words -> return VoiceCommand.AddPerson
                "car" in words -> return VoiceCommand.AddCar
                "object" in words || "item" in words || "thing" in words -> return VoiceCommand.AddObject
            }
        }
        return when {
            "history" in words -> VoiceCommand.History
            "saved" in words || "people" in words -> VoiceCommand.Saved
            "home" in words || "main" in words -> VoiceCommand.Home
            "back" in words || "previous" in words || "return" in words -> VoiceCommand.Back
            "camera" in words -> VoiceCommand.SwitchCamera
            "live" in words -> VoiceCommand.LiveScan
            "full" in words || t.contains("scan the room") -> VoiceCommand.FullScan
            "repeat" in words || t.contains("again") -> VoiceCommand.Repeat
            "text" in words || "log" in words -> VoiceCommand.ReadText
            "start" in words || "begin" in words || "go" in words -> VoiceCommand.Start
            "stop" in words || "finish" in words || "end" in words || "cancel" in words -> VoiceCommand.Stop
            "search" in words -> VoiceCommand.Search("")
            else -> VoiceCommand.Unknown
        }
    }
}
