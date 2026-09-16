package com.classroomscanner.core

import kotlin.math.roundToInt

/** Gray-world white balance: scale R, G and B so the frame's average color becomes neutral. */
object WhiteBalance {
    private const val MIN_GAIN = 0.6f
    private const val MAX_GAIN = 1.6f

    data class Gains(val r: Float, val g: Float, val b: Float)

    /** [avgR], [avgG] and [avgB] are the frame's mean channel values in 0..255. */
    fun gains(avgR: Float, avgG: Float, avgB: Float): Gains {
        val gray = (avgR + avgG + avgB) / 3f
        if (gray < 1f) return Gains(1f, 1f, 1f)
        return Gains(gain(gray, avgR), gain(gray, avgG), gain(gray, avgB))
    }

    /** Applies [g] to a packed 0xRRGGBB color (alpha is dropped). */
    fun apply(rgb: Int, g: Gains): Int {
        val r = channel((rgb shr 16) and 0xFF, g.r)
        val gr = channel((rgb shr 8) and 0xFF, g.g)
        val b = channel(rgb and 0xFF, g.b)
        return (r shl 16) or (gr shl 8) or b
    }

    private fun gain(gray: Float, avg: Float): Float =
        if (avg < 1f) MAX_GAIN else (gray / avg).coerceIn(MIN_GAIN, MAX_GAIN)

    private fun channel(value: Int, gain: Float): Int = (value * gain).roundToInt().coerceIn(0, 255)
}
