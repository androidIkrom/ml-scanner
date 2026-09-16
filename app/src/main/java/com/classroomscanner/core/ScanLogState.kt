package com.classroomscanner.core

/** One line of the scan log: what was said and when (epoch milliseconds). */
data class LogEntry(val timeMs: Long, val text: String)

/** Immutable log of the current scan; [summary] is set when the scan ends. */
data class ScanLogState(
    val entries: List<LogEntry> = emptyList(),
    val summary: String? = null,
) {
    fun add(timeMs: Long, text: String): ScanLogState =
        if (text.isBlank()) this else copy(entries = entries + LogEntry(timeMs, text.trim()))

    fun finish(summaryText: String): ScanLogState = copy(summary = summaryText)
}
