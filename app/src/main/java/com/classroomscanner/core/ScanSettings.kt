package com.classroomscanner.core

import kotlin.math.roundToInt

enum class CameraFacing { BACK, FRONT }

enum class Compute { CPU, GPU }

/**
 * Which detector runs: SSD MobileNet V2 is the lightest, EfficientDet-Lite0 sits in the middle and
 * Lite2 sees the most. All three are Google's COCO models and all run on the phone.
 */
enum class ModelChoice { LIGHT, FAST, ACCURATE }

/**
 * Everything the user picks on the Scan settings screen. Out of the box the app runs on the
 * graphics chip and only reports what it is sure about; the user can change both.
 */
data class ScanSettings(
    val camera: CameraFacing = CameraFacing.BACK,
    val compute: Compute = Compute.GPU,
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
        /** The surest setting by default: fewer things reported, and no wrong ones. */
        const val DEFAULT_MIN_SCORE = MIN_SCORE_HIGH
    }
}
