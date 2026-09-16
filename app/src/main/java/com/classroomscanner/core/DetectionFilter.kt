package com.classroomscanner.core

import kotlin.math.roundToInt

/**
 * Per-label confidence thresholds. [minScore] is the user's confidence setting for ordinary objects.
 * People are often far away or half hidden behind desks, so they are accepted 0.2 earlier,
 * but never below the detector's own threshold.
 */
class DetectionFilter(minScore: Float = ScanSettings.DEFAULT_MIN_SCORE) {
    private val defaultTenths = (ScanSettings(minScore = minScore).normalized().minScore * 10).roundToInt()
    private val defaultMin = defaultTenths / 10f
    private val personMin = maxOf(DETECTOR_THRESHOLD, (defaultTenths - PERSON_BONUS_TENTHS) / 10f)

    fun keep(label: String, score: Float): Boolean =
        score >= if (label == PERSON) personMin else defaultMin

    companion object {
        /** Threshold given to the detector itself; must not be above any per-label threshold. */
        const val DETECTOR_THRESHOLD = 0.3f
        private const val PERSON_BONUS_TENTHS = 2
        private const val PERSON = "person"
    }
}
