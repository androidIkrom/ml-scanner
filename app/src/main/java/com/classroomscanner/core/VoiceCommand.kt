package com.classroomscanner.core

sealed interface VoiceCommand {
    data object FullScan : VoiceCommand
    data object LiveScan : VoiceCommand
    data object History : VoiceCommand
    data object Saved : VoiceCommand
    data class Search(val query: String) : VoiceCommand
    data object Unknown : VoiceCommand
}

/** Maps a spoken Home command to an action. */
object VoiceCommandParser {
    private val search = Regex("^(search for|search|find|where is|where are|wheres|look for|locate)\\s+(.+)$")

    fun parse(text: String): VoiceCommand {
        val t = text.lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        search.find(t)?.let { return VoiceCommand.Search(it.groupValues[2]) }
        val words = t.split(' ').toSet()
        return when {
            "history" in words -> VoiceCommand.History
            "saved" in words -> VoiceCommand.Saved
            "live" in words -> VoiceCommand.LiveScan
            "full" in words || t.contains("scan the room") -> VoiceCommand.FullScan
            else -> VoiceCommand.Unknown
        }
    }
}
