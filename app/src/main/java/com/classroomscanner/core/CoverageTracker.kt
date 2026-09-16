package com.classroomscanner.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Tracks which parts of the circle the camera has pointed at during a scan. */
class CoverageTracker(private val binCount: Int = 36) {

    private val covered = BooleanArray(binCount)
    private val binSize = 360f / binCount

    fun mark(relHeading: Float) {
        val bin = (AngleMath.normalize(relHeading) / binSize).toInt().coerceIn(0, binCount - 1)
        covered[bin] = true
    }

    /** Marks every bin swept between two consecutive headings; a big jump is treated as a glitch. */
    fun markArc(from: Float, to: Float) {
        val d = AngleMath.diff(to, from)
        if (abs(d) > MAX_ARC_DEG) {
            mark(to)
            return
        }
        val steps = max(ceil(abs(d) / STEP_DEG).toInt(), 1)
        for (i in 0..steps) mark(from + d * i / steps)
    }

    fun coveredBins(): Int = covered.count { it }

    fun percent(): Int = coveredBins() * 100 / binCount

    fun isComplete(): Boolean = covered.all { it }

    fun snapshot(): BooleanArray = covered.copyOf()

    private companion object {
        const val MAX_ARC_DEG = 45f
        const val STEP_DEG = 5f
    }
}
