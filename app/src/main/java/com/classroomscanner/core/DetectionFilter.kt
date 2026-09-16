package com.classroomscanner.core

/**
 * Per-label confidence thresholds. People are often far away or half hidden behind desks, so they are
 * accepted at a lower score; everything else keeps the stricter default to avoid false objects.
 */
object DetectionFilter {
    /** Threshold given to the detector itself; must not be above any per-label threshold. */
    const val DETECTOR_THRESHOLD = 0.3f

    private const val DEFAULT_MIN_SCORE = 0.5f
    private val minScore = mapOf("person" to 0.3f)

    fun keep(label: String, score: Float): Boolean = score >= (minScore[label] ?: DEFAULT_MIN_SCORE)
}
