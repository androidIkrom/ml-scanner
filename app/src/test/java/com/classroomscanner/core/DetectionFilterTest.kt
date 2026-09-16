package com.classroomscanner.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionFilterTest {
    private val default = DetectionFilter()

    @Test
    fun peopleAreKeptFromTheLowerThreshold() {
        assertTrue(default.keep("person", 0.30f))
        assertTrue(default.keep("person", 0.45f))
        assertFalse(default.keep("person", 0.29f))
    }

    @Test
    fun otherObjectsStillNeedTheDefaultThreshold() {
        assertFalse(default.keep("chair", 0.45f))
        assertTrue(default.keep("chair", 0.50f))
        assertTrue(default.keep("laptop", 0.9f))
    }

    @Test
    fun detectorThresholdIsTheLowestPerLabelThreshold() {
        assertTrue(DetectionFilter.DETECTOR_THRESHOLD <= 0.30f)
        assertTrue(default.keep("person", DetectionFilter.DETECTOR_THRESHOLD))
    }

    @Test
    fun strictSettingRaisesBothThresholds() {
        val f = DetectionFilter(0.7f)
        assertFalse(f.keep("chair", 0.69f))
        assertTrue(f.keep("chair", 0.7f))
        assertFalse(f.keep("person", 0.49f))
        assertTrue(f.keep("person", 0.5f))
    }

    @Test
    fun lenientSettingNeverGoesBelowDetectorThreshold() {
        val f = DetectionFilter(0.3f)
        assertTrue(f.keep("chair", 0.3f))
        assertFalse(f.keep("chair", 0.29f))
        assertTrue(f.keep("person", 0.3f))
        assertFalse(f.keep("person", 0.29f))
    }

    @Test
    fun outOfRangeSettingIsClamped() {
        val f = DetectionFilter(0.05f)
        assertFalse(f.keep("chair", 0.29f))
        assertTrue(f.keep("chair", 0.3f))
    }
}
