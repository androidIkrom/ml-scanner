package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkGeometryTest {
    @Test
    fun horizonIsTheImageMiddleWhenThePhoneIsUpright() {
        assertEquals(240f, WalkGeometry.horizonY(0f, 600f, 480), 0.01f)
    }

    @Test
    fun lookingDownMovesTheHorizonUp() {
        // Tilted 20 degrees down: the horizon rises by focal * tan(20).
        assertEquals(240f - 600f * 0.36397f, WalkGeometry.horizonY(-20f, 600f, 480), 0.5f)
    }

    @Test
    fun distanceFromTheBottomEdgeOfABox() {
        // Camera 1.4 m high, focal 600 px, object bottom 120 px below the horizon.
        assertEquals(7f, WalkGeometry.distanceFromBottom(360f, 240f, 600f, 1.4f)!!, 0.01f)
        assertEquals(1.4f, WalkGeometry.distanceFromBottom(840f, 240f, 600f, 1.4f)!!, 0.01f)
    }

    @Test
    fun objectsAtOrAboveTheHorizonHaveNoGroundDistance() {
        assertNull(WalkGeometry.distanceFromBottom(240f, 240f, 600f, 1.4f))
        assertNull(WalkGeometry.distanceFromBottom(100f, 240f, 600f, 1.4f))
    }

    @Test
    fun stepsAndStepLength() {
        assertEquals(0.7055f, WalkGeometry.stepLength(1.70f), 0.001f)
        assertEquals(4, WalkGeometry.steps(3f, 0.7f))
        assertEquals(1, WalkGeometry.steps(0.2f, 0.7f))
    }
}

class WalkZoneTest {
    @Test
    fun zonesByHorizontalCenter() {
        assertEquals(WalkZone.LEFT, WalkZone.of(0.2f))
        assertEquals(WalkZone.AHEAD, WalkZone.of(0.5f))
        assertEquals(WalkZone.RIGHT, WalkZone.of(0.8f))
    }

    @Test
    fun zoneWords() {
        assertEquals("on your left", WalkZone.LEFT.phrase)
        assertEquals("ahead", WalkZone.AHEAD.phrase)
    }
}

class HazardPolicyTest {
    @Test
    fun hazardClasses() {
        assertTrue(HazardPolicy.isHazard("person"))
        assertTrue(HazardPolicy.isHazard("car"))
        assertTrue(HazardPolicy.isHazard("chair"))
        assertFalse(HazardPolicy.isHazard("book"))
        assertFalse(HazardPolicy.isHazard("clock"))
    }

    @Test
    fun urgencyByDistance() {
        assertEquals(Urgency.CLOSE, HazardPolicy.urgency(1.2f))
        assertEquals(Urgency.NEAR, HazardPolicy.urgency(2.5f))
        assertEquals(Urgency.FAR, HazardPolicy.urgency(6f))
    }
}

class WalkPhrasesTest {
    @Test
    fun hazardSentences() {
        val hazard = Hazard("chair", 2.8f, WalkZone.AHEAD)
        assertEquals("Chair ahead, four steps.", WalkPhrases.hazard(hazard, stepLength = 0.7f))
        assertEquals("Chair ahead, 3 metres.", WalkPhrases.hazard(hazard, stepLength = null))
    }

    @Test
    fun closeHazardsAreShort() {
        val hazard = Hazard("person", 0.9f, WalkZone.LEFT)
        assertEquals("Person close on your left.", WalkPhrases.hazard(hazard, stepLength = 0.7f))
    }

    @Test
    fun groundAndLights() {
        assertEquals("Road under you.", WalkPhrases.ground("road"))
        assertEquals("Red light seen.", WalkPhrases.trafficLight(TrafficLightColor.RED))
    }
}

class WalkAlertsTest {
    private val chairAhead = Hazard("chair", 2.5f, WalkZone.AHEAD)
    private val chairCloser = Hazard("chair", 1.1f, WalkZone.AHEAD)
    private val carLeft = Hazard("car", 2.0f, WalkZone.LEFT)

    @Test
    fun speaksTheNearestHazardAheadOnce() {
        val alerts = WalkAlerts()
        assertEquals(chairAhead, alerts.next(0, listOf(chairAhead)))
        assertNull(alerts.next(500, listOf(chairAhead)))
    }

    @Test
    fun speaksAgainWhenItComesCloser() {
        val alerts = WalkAlerts()
        alerts.next(0, listOf(chairAhead))
        assertEquals(chairCloser, alerts.next(1_000, listOf(chairCloser)))
    }

    @Test
    fun repeatsOnlyAfterTheQuietTime() {
        val alerts = WalkAlerts(repeatMs = 6_000)
        alerts.next(0, listOf(chairAhead))
        assertNull(alerts.next(3_000, listOf(chairAhead)))
        assertEquals(chairAhead, alerts.next(7_000, listOf(chairAhead)))
    }

    @Test
    fun thingsAheadComeBeforeThingsToTheSide() {
        val alerts = WalkAlerts()
        assertEquals(chairAhead, alerts.next(0, listOf(carLeft, chairAhead)))
    }

    @Test
    fun farThingsAreIgnored() {
        val alerts = WalkAlerts()
        assertNull(alerts.next(0, listOf(Hazard("car", 8f, WalkZone.AHEAD))))
        assertNull(alerts.next(0, emptyList()))
    }

    @Test
    fun beepsGetFasterAsThingsComeCloser() {
        assertNull(WalkAlerts.beepIntervalMs(null))
        assertNull(WalkAlerts.beepIntervalMs(5f))
        assertEquals(150L, WalkAlerts.beepIntervalMs(0.4f))
        assertEquals(1_000L, WalkAlerts.beepIntervalMs(4f))
    }
}

class TrafficLightColorTest {
    @Test
    fun theBiggestColourWins() {
        assertEquals(TrafficLightColor.RED, TrafficLightColor.of(red = 120, yellow = 10, green = 30))
        assertEquals(TrafficLightColor.GREEN, TrafficLightColor.of(red = 20, yellow = 10, green = 130))
        assertEquals(TrafficLightColor.YELLOW, TrafficLightColor.of(red = 20, yellow = 90, green = 30))
    }

    @Test
    fun tooFewColouredPixelsMeanNoAnswer() {
        assertNull(TrafficLightColor.of(red = 4, yellow = 2, green = 1))
    }
}

class BeaconTest {
    // Two points about 111 metres apart on the same meridian.
    private val lat1 = 41.3111
    private val lon1 = 69.2797
    private val lat2 = 41.3121
    private val lon2 = 69.2797

    @Test
    fun distanceAndBearing() {
        assertEquals(111f, Beacon.distanceMetres(lat1, lon1, lat2, lon2), 2f)
        assertEquals(0f, Beacon.bearingDeg(lat1, lon1, lat2, lon2), 1f)
        assertEquals(180f, Beacon.bearingDeg(lat2, lon2, lat1, lon1), 1f)
    }

    @Test
    fun clockDirectionUsesTheWayThePhoneFaces() {
        assertEquals(12, Beacon.clockHour(bearingDeg = 0f, headingDeg = 0f))
        assertEquals(3, Beacon.clockHour(bearingDeg = 90f, headingDeg = 0f))
        assertEquals(12, Beacon.clockHour(bearingDeg = 90f, headingDeg = 90f))
        assertEquals(9, Beacon.clockHour(bearingDeg = 0f, headingDeg = 90f))
    }

    @Test
    fun phrase() {
        assertEquals("Home, 120 metres, 2 o'clock.", Beacon.phrase("Home", 120f, 2))
        assertEquals("Home, 5 metres, 12 o'clock.", Beacon.phrase("Home", 5.4f, 12))
    }
}
