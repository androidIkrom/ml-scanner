package com.classroomscanner.core

enum class Sector8 { FRONT, FRONT_RIGHT, RIGHT, BEHIND_RIGHT, BEHIND, BEHIND_LEFT, LEFT, FRONT_LEFT }

enum class Sector4(val phrase: String) {
    FRONT("in front"),
    RIGHT("on your right"),
    BEHIND("behind you"),
    LEFT("on your left"),
}

/** Angles are degrees, clockwise, relative to the direction the user faced when the scan started. */
object AngleMath {

    fun normalize(deg: Float): Float {
        val r = deg % 360f
        val n = if (r < 0f) r + 360f else r
        return if (n >= 360f) 0f else n
    }

    /** Shortest signed difference `a - b`, in (-180, 180]. */
    fun diff(a: Float, b: Float): Float {
        val d = normalize(a - b)
        return if (d > 180f) d - 360f else d
    }

    fun sector8(relDeg: Float): Sector8 =
        Sector8.entries[((normalize(relDeg) + 22.5f) / 45f).toInt() % 8]

    fun sector4(relDeg: Float): Sector4 =
        Sector4.entries[((normalize(relDeg) + 45f) / 90f).toInt() % 4]

    /** Running circular mean: [mean] already averages [count] samples; fold in [sample]. */
    fun weightedMean(mean: Float, count: Int, sample: Float): Float =
        normalize(mean + diff(sample, mean) / (count + 1))
}
