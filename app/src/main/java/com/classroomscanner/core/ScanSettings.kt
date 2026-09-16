package com.classroomscanner.core

import kotlin.math.roundToInt

enum class CameraFacing { BACK, FRONT }

enum class Compute { CPU, GPU }

enum class ModelChoice { FAST, ACCURATE }

/** Everything the user picks on the Scan settings screen. */
data class ScanSettings(
    val camera: CameraFacing = CameraFacing.BACK,
    val compute: Compute = Compute.CPU,
    val model: ModelChoice = ModelChoice.ACCURATE,
    val minScore: Float = DEFAULT_MIN_SCORE,
    val speechOn: Boolean = true,
    val colorsOn: Boolean = true,
) {
    /** Copy with the confidence clamped to [MIN_SCORE_LOW]..[MIN_SCORE_HIGH] and rounded to one decimal. */
    fun normalized(): ScanSettings {
        val score = if (minScore.isNaN()) DEFAULT_MIN_SCORE else minScore.coerceIn(MIN_SCORE_LOW, MIN_SCORE_HIGH)
        return copy(minScore = (score * 10).roundToInt() / 10f)
    }

    companion object {
        const val MIN_SCORE_LOW = 0.3f
        const val MIN_SCORE_HIGH = 0.7f
        const val DEFAULT_MIN_SCORE = 0.5f
    }
}
