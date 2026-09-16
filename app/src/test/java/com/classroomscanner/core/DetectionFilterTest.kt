package com.classroomscanner.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionFilterTest {

    @Test
    fun peopleAreKeptFromTheLowerThreshold() {
        assertTrue(DetectionFilter.keep("person", 0.30f))
        assertTrue(DetectionFilter.keep("person", 0.45f))
        assertFalse(DetectionFilter.keep("person", 0.29f))
    }

    @Test
    fun otherObjectsStillNeedTheDefaultThreshold() {
        assertFalse(DetectionFilter.keep("chair", 0.45f))
        assertTrue(DetectionFilter.keep("chair", 0.50f))
        assertTrue(DetectionFilter.keep("laptop", 0.9f))
    }

    @Test
    fun detectorThresholdIsTheLowestPerLabelThreshold() {
        assertTrue(DetectionFilter.DETECTOR_THRESHOLD <= 0.30f)
        assertTrue(DetectionFilter.keep("person", DetectionFilter.DETECTOR_THRESHOLD))
    }
}
