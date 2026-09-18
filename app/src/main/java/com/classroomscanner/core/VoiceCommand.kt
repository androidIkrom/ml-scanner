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

    /** Working the screen the user is on. [Start] presses its main button. */
    data object Start : VoiceCommand
    data object Stop : VoiceCommand
    data object SwitchCamera : VoiceCommand
    data object Repeat : VoiceCommand
    data object ReadText : VoiceCommand
    data object Delete : VoiceCommand

    /** "Who is this" and "what is this" during a scan. */
    data object IdentifyPerson : VoiceCommand
    data object IdentifyThing : VoiceCommand

    /** Walk mode. */
    data object Walk : VoiceCommand
    data class SavePlace(val name: String) : VoiceCommand
    data class GoTo(val name: String) : VoiceCommand

    /** Switches the microphone off. */
    data object StopListening : VoiceCommand

    /** Explains a [ScreenHelp] topic; an empty topic means the screen the user is on. */
    data class Help(val topic: String) : VoiceCommand

    /** Turns the spoken screen introductions on or off. */
    data class Learner(val on: Boolean) : VoiceCommand

    data object Unknown : VoiceCommand
}

/**
 * Maps spoken commands to actions. People say the same thing in many ways — "full scan", "open full
 * scan", "begin full scan" — so this reads the words that carry the meaning instead of matching
 * whole sentences. English only, because the app speaks English.
 */
object VoiceCommandParser {
    private val savePlace = Regex("^(save this place as|save place as|save place|save this place)\\s+(.+)$")
    private val goTo = Regex("^(take me to|guide me to|go to|walk me to)\\s+(.+)$")
    private val search = Regex(
        "(?:^|\\b)(?:search for|search|find|where is|where are|wheres|look for|locate)\\s+(.+)$"
    )

    fun parse(text: String): VoiceCommand {
        val t = text.lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.isEmpty()) return VoiceCommand.Unknown
        val words = t.split(' ').toSet()

        // The microphone comes first: "stop listening" must not stop a scan instead.
        if (t.contains("listening") || t.contains("microphone") || t.contains("mic off") ||
            t.contains("voice off")
        ) {
            return VoiceCommand.StopListening
        }
        savePlace.find(t)?.let { return VoiceCommand.SavePlace(it.groupValues[2].trim()) }
        goTo.find(t)?.let { return VoiceCommand.GoTo(it.groupValues[2].trim()) }
        if ("learner" in words || "learning" in words) return VoiceCommand.Learner(isOn(words))
        if ("walk" in words || "walking" in words) return VoiceCommand.Walk

        questionCommand(t, words)?.let { return it }
        addCommand(words)?.let { return it }
        if (isSearchScreen(words)) return VoiceCommand.Search("")
        search.find(t)?.let { return VoiceCommand.Search(it.groupValues[1].trim()) }
        return screenCommand(t, words)
    }

    /** Questions: who is this, what is this, what is search, help. */
    private fun questionCommand(t: String, words: Set<String>): VoiceCommand? {
        val aboutHere = t.contains("this screen") || Regex("\\bhere\\b").containsMatchIn(t)
        if (t.startsWith("who is") || t.startsWith("whos") || t.startsWith("who are")) {
            return VoiceCommand.IdentifyPerson
        }
        if ("identify" in words) {
            return if ("who" in words || "person" in words) {
                VoiceCommand.IdentifyPerson
            } else {
                VoiceCommand.IdentifyThing
            }
        }
        if (t.contains("what did you say") || t.contains("what was that")) return VoiceCommand.Repeat
        if (t.startsWith("what") && !aboutHere) {
            if (t.contains("looking at") || t.contains("in front")) return VoiceCommand.IdentifyThing
            val rest = t.removePrefix("what is").removePrefix("whats").removePrefix("what does")
                .removePrefix("what do").trim()
            if (rest.isEmpty() || rest == "this" || rest == "that") return VoiceCommand.IdentifyThing
            ScreenHelp.topicOf(rest)?.let { return VoiceCommand.Help(it) }
            return VoiceCommand.IdentifyThing
        }
        if (t.startsWith("tell me about") || t.startsWith("explain") || t.startsWith("how do i use")) {
            return VoiceCommand.Help(ScreenHelp.topicOf(t) ?: "")
        }
        if (t == "help" || t.startsWith("help me") || aboutHere) return VoiceCommand.Help("")
        return null
    }

    /** Adding a saved person, car or object, however the user words it. */
    private fun addCommand(words: Set<String>): VoiceCommand? {
        val adding = words.any { it in setOf("add", "create", "new", "register", "save", "make") }
        if (!adding) return null
        return when {
            words.any { it in setOf("person", "people", "face", "someone") } -> VoiceCommand.AddPerson
            "car" in words || "vehicle" in words -> VoiceCommand.AddCar
            words.any { it in setOf("object", "item", "thing", "things") } -> VoiceCommand.AddObject
            else -> null
        }
    }

    private fun isSearchScreen(words: Set<String>): Boolean =
        "search" in words && words.any { it in setOf("open", "show", "screen", "page") }

    /** Everything else: screens, and the buttons of the screen the user is on. */
    private fun screenCommand(t: String, words: Set<String>): VoiceCommand = when {
        "history" in words || t.contains("past scans") -> VoiceCommand.History
        "saved" in words || "people" in words || t.contains("my things") -> VoiceCommand.Saved
        "live" in words -> VoiceCommand.LiveScan
        "full" in words || t.contains("scan the room") -> VoiceCommand.FullScan
        "camera" in words -> VoiceCommand.SwitchCamera
        "delete" in words || "remove" in words -> VoiceCommand.Delete
        "repeat" in words || t.contains("again") || t.contains("one more time") -> VoiceCommand.Repeat
        "text" in words || "log" in words || t.contains("read it") -> VoiceCommand.ReadText
        "home" in words || t.contains("main menu") -> VoiceCommand.Home
        words.any { it in setOf("back", "previous", "exit", "leave", "close") } -> VoiceCommand.Back
        words.any { it in setOf("stop", "finish", "end", "cancel") } -> VoiceCommand.Stop
        words.any { it in setOf("start", "begin", "enter", "ok", "okay", "yes", "confirm", "press", "button") } ||
            t == "do it" || t == "go ahead" -> VoiceCommand.Start
        "search" in words -> VoiceCommand.Search("")
        else -> VoiceCommand.Unknown
    }

    /** Words that switch something on; anything about stopping or leaving switches it off. */
    private fun isOn(words: Set<String>): Boolean =
        "off" !in words && "stop" !in words && "disable" !in words && "no" !in words
}
