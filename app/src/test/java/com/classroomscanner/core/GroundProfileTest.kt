package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The grids here are built the way the phone sees them: for each image row the distance a ray
 * travels before it meets a surface at a chosen height.
 */
class GroundProfileTest {
    private val width = 12
    private val height = 40
    private val focal = 30f
    private val horizon = 4f
    private val cameraHeight = 1.4f

    /** Distance along the ray from a row to a surface [heightM] above the floor, or 0 when it misses. */
    private fun rayTo(row: Int, heightM: Float): Float {
        val alpha = atan((row - horizon) / focal)
        if (alpha <= 0f) return 0f
        val drop = cameraHeight - heightM
        val distance = drop / sin(alpha)
        return if (distance > 0f) distance else 0f
    }

    private fun grid(heightAt: (Float) -> Float): FloatArray {
        val out = FloatArray(width * height)
        for (row in 0 until height) {
            // Where the floor would be for this row, to know what is being looked at.
            val floorDistance = rayTo(row, 0f)
            val alpha = atan((row - horizon) / focal)
            val forward = floorDistance * cos(alpha)
            val surface = heightAt(forward)
            val ray = rayTo(row, surface)
            for (col in 0 until width) out[row * width + col] = ray
        }
        return out
    }

    @Test
    fun flatFloorHasNothingToReport() {
        val found = GroundProfile.analyze(grid { 0f }, width, height, focal, horizon, cameraHeight)
        assertNull(found.stepUpM)
        assertNull(found.dropM)
    }

    @Test
    fun aStepUpIsFound() {
        // The floor rises by 18 cm from two metres on.
        val found = GroundProfile.analyze(
            grid { forward -> if (forward >= 2f) 0.18f else 0f },
            width, height, focal, horizon, cameraHeight,
        )
        assertEquals(2f, found.stepUpM!!, 0.6f)
        assertNull(found.dropM)
    }

    @Test
    fun aDropIsFound() {
        // The floor falls away by 20 cm from about two and a half metres on.
        val found = GroundProfile.analyze(
            grid { forward -> if (forward >= 2.5f) -0.2f else 0f },
            width, height, focal, horizon, cameraHeight,
        )
        assertEquals(2.5f, found.dropM!!, 0.8f)
        assertNull(found.stepUpM)
    }

    @Test
    fun missingDepthIsNotADrop() {
        val found = GroundProfile.analyze(FloatArray(width * height), width, height, focal, horizon, cameraHeight)
        assertNull(found.stepUpM)
        assertNull(found.dropM)
    }

    @Test
    fun severalStepsReadAsStairs() {
        val found = GroundProfile.analyze(
            grid { forward ->
                when {
                    forward >= 2.4f -> 0.45f
                    forward >= 2.1f -> 0.3f
                    forward >= 1.8f -> 0.15f
                    else -> 0f
                }
            },
            width, height, focal, horizon, cameraHeight,
        )
        assertTrue(found.stairs)
        assertEquals(1.8f, found.stepUpM!!, 0.6f)
    }

    @Test
    fun wordsForWhatWasFound() {
        assertEquals(
            "Step down ahead, three steps.",
            WalkPhrases.ground(GroundFinding(stepUpM = null, dropM = 2f, stairs = false), stepLength = 0.7f),
        )
        assertEquals(
            "Stairs up ahead, 2 metres.",
            WalkPhrases.ground(GroundFinding(stepUpM = 2f, dropM = null, stairs = true), stepLength = null),
        )
        assertEquals(
            "Step up ahead, three steps.",
            WalkPhrases.ground(GroundFinding(stepUpM = 2f, dropM = null, stairs = false), stepLength = 0.7f),
        )
        assertNull(WalkPhrases.ground(GroundFinding(null, null, false), stepLength = 0.7f))
    }
}
