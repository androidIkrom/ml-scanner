package com.classroomscanner.core

/** Maps an HSV color to one of 11 basic English color names. */
object ColorMapper {

    fun nameFromHsv(h: Float, s: Float, v: Float, frameIsDark: Boolean = false): String? {
        if (frameIsDark) return null
        if (v < 0.2f) return "black"
        if (s < 0.15f) return if (v > 0.8f) "white" else "gray"
        return when {
            h < 15f || h >= 345f -> if (s < 0.5f && v > 0.7f) "pink" else "red"
            h < 45f -> if (v < 0.6f) "brown" else "orange"
            h < 70f -> "yellow"
            h < 170f -> "green"
            h < 260f -> "blue"
            h < 290f -> "purple"
            else -> "pink"
        }
    }
}
