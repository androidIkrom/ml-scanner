package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSettingsTest {

    @Test
    fun defaults() {
        val s = ScanSettings()
        assertEquals(CameraFacing.BACK, s.camera)
        assertEquals(Compute.GPU, s.compute)
        assertEquals(ModelChoice.ACCURATE, s.model)
        assertEquals(0.7f, s.minScore, 0f)
        assertTrue(s.speechOn)
        assertTrue(s.colorsOn)
    }

    @Test
    fun confidenceIsClamped() {
        assertEquals(0.3f, ScanSettings(minScore = 0.1f).normalized().minScore, 0f)
        assertEquals(0.7f, ScanSettings(minScore = 0.95f).normalized().minScore, 0f)
    }

    @Test
    fun confidenceIsRoundedToOneDecimal() {
        assertEquals(0.6f, ScanSettings(minScore = 0.62f).normalized().minScore, 0f)
        assertEquals(0.5f, ScanSettings(minScore = 0.5f).normalized().minScore, 0f)
    }

    @Test
    fun nanFallsBackToDefault() {
        assertEquals(0.7f, ScanSettings(minScore = Float.NaN).normalized().minScore, 0f)
    }
}
