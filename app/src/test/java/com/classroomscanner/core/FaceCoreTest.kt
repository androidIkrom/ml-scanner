package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMatcherTest {
    private val eps = 0.0001f

    @Test
    fun cosineOfSameAndOppositeVectors() {
        assertEquals(1f, FaceMatcher.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)), eps)
        assertEquals(-1f, FaceMatcher.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), eps)
        assertEquals(0f, FaceMatcher.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), eps)
    }

    @Test
    fun cosineIsZeroForBadInput() {
        assertEquals(0f, FaceMatcher.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), eps)
        assertEquals(0f, FaceMatcher.cosine(floatArrayOf(1f), floatArrayOf(1f, 1f)), eps)
    }

    @Test
    fun bestMatchPicksTheMostSimilarFaceAboveThreshold() {
        val known = listOf(
            KnownFace(1L, "Ali", floatArrayOf(1f, 0f, 0f)),
            KnownFace(1L, "Ali", floatArrayOf(0.9f, 0.1f, 0f)),
            KnownFace(2L, "Vali", floatArrayOf(0f, 1f, 0f)),
        )
        val match = FaceMatcher.bestMatch(floatArrayOf(0.1f, 1f, 0f), known)!!
        assertEquals(2L, match.personId)
        assertEquals("Vali", match.name)
    }

    @Test
    fun noMatchBelowThresholdOrWhenEmpty() {
        val known = listOf(KnownFace(1L, "Ali", floatArrayOf(1f, 0f)))
        assertNull(FaceMatcher.bestMatch(floatArrayOf(0f, 1f), known))
        assertNull(FaceMatcher.bestMatch(floatArrayOf(1f, 0f), emptyList()))
    }

    @Test
    fun theThresholdIsStrictEnoughToKeepStrangersApart() {
        assertEquals(0.5f, FaceMatcher.THRESHOLD, 0f)
        assertEquals(0.08f, FaceMatcher.MARGIN, 0f)
    }

    @Test
    fun oneLuckySampleIsNotEnough() {
        // Ali has one sample that happens to look like the face, and many that do not.
        val ali = listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, 1f))
            .map { KnownFace(1L, "Ali", it) }
        // Vali looks like the face in most of his samples.
        val vali = listOf(floatArrayOf(0.9f, 0.3f, 0f), floatArrayOf(0.85f, 0.35f, 0f), floatArrayOf(0.9f, 0.25f, 0f))
            .map { KnownFace(2L, "Vali", it) }
        val match = FaceMatcher.bestMatch(floatArrayOf(0.95f, 0.2f, 0f), ali + vali)
        assertEquals("Vali", match?.name)
    }

    @Test
    fun twoPeopleTooCloseToCallIsNobody() {
        val known = listOf(
            KnownFace(1L, "Ali", floatArrayOf(1f, 0.05f)),
            KnownFace(2L, "Vali", floatArrayOf(1f, -0.05f)),
        )
        assertNull(FaceMatcher.bestMatch(floatArrayOf(1f, 0f), known))
    }

    @Test
    fun aClearWinnerIsNamed() {
        val known = listOf(
            KnownFace(1L, "Ali", floatArrayOf(1f, 0f)),
            KnownFace(2L, "Vali", floatArrayOf(0f, 1f)),
        )
        assertEquals("Ali", FaceMatcher.bestMatch(floatArrayOf(1f, 0.1f), known)?.name)
    }

    @Test
    fun onlyGoodFacesAreCompared() {
        assertTrue(FaceQuality.usable(sizePx = 120, yawDeg = 10f, pitchDeg = 5f))
        assertFalse(FaceQuality.usable(sizePx = 40, yawDeg = 0f, pitchDeg = 0f))
        assertFalse(FaceQuality.usable(sizePx = 120, yawDeg = 50f, pitchDeg = 0f))
        assertFalse(FaceQuality.usable(sizePx = 120, yawDeg = 0f, pitchDeg = -40f))
    }
}

class FaceNetPreprocessTest {
    private val eps = 0.0001f

    @Test
    fun standardizesToZeroMeanUnitStd() {
        val out = FaceNetPreprocess.standardize(floatArrayOf(0f, 2f, 4f, 6f))
        val s = kotlin.math.sqrt(5f)
        assertEquals(-3f / s, out[0], eps)
        assertEquals(-1f / s, out[1], eps)
        assertEquals(1f / s, out[2], eps)
        assertEquals(3f / s, out[3], eps)
    }

    @Test
    fun constantImageDoesNotDivideByZero() {
        val out = FaceNetPreprocess.standardize(floatArrayOf(7f, 7f, 7f, 7f))
        out.forEach { assertEquals(0f, it, eps) }
    }
}

class EnrollmentGuideTest {

    @Test
    fun posesFromHeadAngles() {
        assertEquals(Pose.STRAIGHT, EnrollmentGuide.poseOf(yaw = 3f, pitch = -4f))
        assertEquals(Pose.LEFT, EnrollmentGuide.poseOf(yaw = 25f, pitch = 5f))
        assertEquals(Pose.RIGHT, EnrollmentGuide.poseOf(yaw = -30f, pitch = 0f))
        assertEquals(Pose.UP, EnrollmentGuide.poseOf(yaw = 2f, pitch = 15f))
        assertEquals(Pose.DOWN, EnrollmentGuide.poseOf(yaw = -5f, pitch = -14f))
        assertNull(EnrollmentGuide.poseOf(yaw = 15f, pitch = 0f))
        assertNull(EnrollmentGuide.poseOf(yaw = 25f, pitch = 20f))
    }

    @Test
    fun walksThroughAllPosesAndReportsProgress() {
        val g = EnrollmentGuide(samplesPerPose = 2)
        assertEquals(Pose.STRAIGHT, g.currentPose)
        assertEquals(0, g.percent())
        assertFalse(g.offer(yaw = 25f, pitch = 0f))
        assertTrue(g.offer(yaw = 0f, pitch = 0f))
        assertEquals(10, g.percent())
        assertFalse(g.poseFinished)
        assertTrue(g.offer(yaw = 0f, pitch = 0f))
        assertTrue(g.poseFinished)
        assertEquals(Pose.LEFT, g.currentPose)
        assertEquals(20, g.percent())
        assertTrue(g.offer(yaw = 25f, pitch = 0f))
        assertFalse(g.poseFinished)
        repeat(1) { g.offer(yaw = 25f, pitch = 0f) }
        repeat(2) { g.offer(yaw = -25f, pitch = 0f) }
        repeat(2) { g.offer(yaw = 0f, pitch = 20f) }
        assertEquals(Pose.DOWN, g.currentPose)
        repeat(2) { g.offer(yaw = 0f, pitch = -20f) }
        assertTrue(g.done)
        assertNull(g.currentPose)
        assertEquals(100, g.percent())
        assertFalse(g.offer(yaw = 0f, pitch = 0f))
    }

    @Test
    fun instructionsAreEnglish() {
        assertEquals("Look straight at the camera.", Pose.STRAIGHT.instruction)
        assertEquals("Turn your head to your left.", Pose.LEFT.instruction)
    }
}

class NamedPeopleTest {

    @Test
    fun plainPersonNextToANamedPersonIsDropped() {
        val out = NamedPeople.dropShadowedPersons(
            listOf(
                ObjectSummary("Ali", 1, null, 10f, isName = true),
                ObjectSummary("person", 1, null, 15f),
                ObjectSummary("person", 2, null, 180f),
            )
        )
        assertEquals(listOf("Ali", "person"), out.map { it.label })
        assertEquals(2, out[1].count)
    }

    @Test
    fun groupKeepsTheUnrecognizedRest() {
        val out = NamedPeople.dropShadowedPersons(
            listOf(
                ObjectSummary("person", 3, null, 0f),
                ObjectSummary("Ali", 1, null, 355f, isName = true),
            )
        )
        assertEquals(1, out.count { it.label == "person" })
        assertEquals(2, out.first { it.label == "person" }.count)
    }
}
