package com.classroomscanner.core

import kotlin.math.abs
import kotlin.math.roundToLong

/** Turns where the target sits in the view into words and beep speed. */
object SearchGuide {
    private const val NEAREST_MS = 150L
    private const val FARTHEST_MS = 1_000L

    fun direction(centerX: Float): String = when {
        centerX < 0.2f -> "far left"
        centerX < 0.4f -> "left"
        centerX <= 0.6f -> "ahead"
        centerX <= 0.8f -> "right"
        else -> "far right"
    }

    fun isCentered(centerX: Float): Boolean = centerX in 0.4f..0.6f

    fun beepIntervalMs(centerX: Float): Long {
        val off = (abs(centerX - 0.5f) / 0.5f).coerceIn(0f, 1f)
        return (NEAREST_MS + (FARTHEST_MS - NEAREST_MS) * off).roundToLong()
    }
}

/** Decides what to say while searching: found, direction changes (throttled) and lost. */
class SearchTracker(
    private val name: String,
    private val lostAfterMs: Long = 3_000,
    private val repeatMs: Long = 2_000,
) {
    private var found = false
    private var lastSeenAt = 0L
    private var lastSpokeAt = 0L
    private var lastPhrase: String? = null

    /** [centerX] is null when the target is not in this frame. Returns text to speak, if any. */
    fun update(nowMs: Long, centerX: Float?): String? {
        if (centerX == null) {
            if (found && nowMs - lastSeenAt >= lostAfterMs) {
                found = false
                lastPhrase = null
                return "Lost it. Turn slowly."
            }
            return null
        }
        lastSeenAt = nowMs
        val phrase = SearchGuide.direction(centerX)
        if (!found) {
            found = true
            return spoke(nowMs, phrase, "Found $name, $phrase.")
        }
        if (phrase != lastPhrase && nowMs - lastSpokeAt >= repeatMs) {
            return spoke(nowMs, phrase, phrase.replaceFirstChar { it.uppercase() } + ".")
        }
        return null
    }

    private fun spoke(nowMs: Long, phrase: String, text: String): String {
        lastPhrase = phrase
        lastSpokeAt = nowMs
        return text
    }
}
