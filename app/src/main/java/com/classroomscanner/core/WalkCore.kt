package com.classroomscanner.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Where something is in front of the walker. */
enum class WalkZone(val phrase: String) {
    LEFT("on your left"),
    AHEAD("ahead"),
    RIGHT("on your right");

    companion object {
        fun of(centerX: Float): WalkZone = when {
            centerX < 0.35f -> LEFT
            centerX <= 0.65f -> AHEAD
            else -> RIGHT
        }
    }
}

enum class Urgency { CLOSE, NEAR, FAR }

/** One thing in the way: what it is, how far in metres, and where. */
data class Hazard(val label: String, val metres: Float, val zone: WalkZone)

/**
 * Distances without a depth sensor: the bottom edge of a box is where the object stands on the
 * floor, so its distance follows from the camera height and how far that edge is below the horizon.
 */
object WalkGeometry {
    /** Average step length for a body height, from the usual 0.415 factor. */
    fun stepLength(heightM: Float): Float = 0.415f * heightM

    /** The image row the horizon sits on; [pitchDeg] is negative when the phone looks down. */
    fun horizonY(pitchDeg: Float, focalPx: Float, imageHeight: Int): Float =
        imageHeight / 2f + focalPx * tan(Math.toRadians(pitchDeg.toDouble())).toFloat()

    /** Metres to where a box's bottom edge meets the floor; null when it is at or above the horizon. */
    fun distanceFromBottom(bottomY: Float, horizonY: Float, focalPx: Float, cameraHeightM: Float): Float? {
        val below = bottomY - horizonY
        if (below <= 1f) return null
        return cameraHeightM * focalPx / below
    }

    /** Steps to cover [metres], rounded to the nearest whole step; always at least one. */
    fun steps(metres: Float, stepLength: Float): Int =
        (metres / stepLength).roundToInt().coerceAtLeast(1)
}

/** Which detector classes matter while walking, and how urgent a distance is. */
object HazardPolicy {
    private val hazards = setOf(
        "person", "bicycle", "car", "motorcycle", "bus", "truck", "train", "boat", "dog", "cat",
        "horse", "cow", "bench", "chair", "couch", "bed", "dining table", "potted plant", "toilet",
        "tv", "refrigerator", "stop sign", "fire hydrant", "parking meter", "traffic light",
        "suitcase", "backpack",
    )

    fun isHazard(label: String): Boolean = label in hazards

    fun urgency(metres: Float): Urgency = when {
        metres < 1.5f -> Urgency.CLOSE
        metres < 3f -> Urgency.NEAR
        else -> Urgency.FAR
    }
}

/** English sentences for walk mode; short, because they are heard while moving. */
object WalkPhrases {
    fun hazard(hazard: Hazard, stepLength: Float?): String {
        val name = hazard.label.replaceFirstChar { it.uppercase() }
        if (HazardPolicy.urgency(hazard.metres) == Urgency.CLOSE) {
            return "$name close ${hazard.zone.phrase}."
        }
        val far = if (stepLength != null) {
            val steps = WalkGeometry.steps(hazard.metres, stepLength)
            "${number(steps)} step${if (steps == 1) "" else "s"}"
        } else {
            "${hazard.metres.roundToInt()} metres"
        }
        return "$name ${hazard.zone.phrase}, $far."
    }

    fun ground(label: String): String = "${label.replaceFirstChar { it.uppercase() }} under you."

    fun trafficLight(color: TrafficLightColor): String =
        "${color.name.lowercase().replaceFirstChar { it.uppercase() }} light seen."

    /** Small counts read better as words. */
    private fun number(n: Int): String = when (n) {
        1 -> "one"
        2 -> "two"
        3 -> "three"
        4 -> "four"
        5 -> "five"
        6 -> "six"
        7 -> "seven"
        8 -> "eight"
        9 -> "nine"
        10 -> "ten"
        else -> n.toString()
    }
}

/**
 * Keeps walk mode quiet: it answers with the one hazard worth saying now, and only when something
 * changed — a new thing ahead, or a known thing that came closer.
 */
class WalkAlerts(private val repeatMs: Long = 6_000) {
    private class Said(val urgency: Urgency, val zone: WalkZone, val at: Long)

    private val said = HashMap<String, Said>()

    /** The hazard to announce now, or null to stay quiet. */
    fun next(nowMs: Long, hazards: List<Hazard>): Hazard? {
        val worth = hazards.filter { HazardPolicy.urgency(it.metres) != Urgency.FAR }
        if (worth.isEmpty()) return null
        val pick = worth.minWithOrNull(
            compareBy({ if (it.zone == WalkZone.AHEAD) 0 else 1 }, { it.metres })
        ) ?: return null
        val before = said[pick.label]
        val urgency = HazardPolicy.urgency(pick.metres)
        val changed = before == null ||
            urgency == Urgency.CLOSE && before.urgency != Urgency.CLOSE ||
            pick.zone != before.zone ||
            nowMs - before.at >= repeatMs
        if (!changed) return null
        said[pick.label] = Said(urgency, pick.zone, nowMs)
        return pick
    }

    fun clear() = said.clear()

    companion object {
        private const val NEAREST_MS = 150L
        private const val FARTHEST_MS = 1_000L
        private const val RANGE_M = 4f

        /** Closer than this the tick is already as fast as it gets. */
        private const val NEAREST_M = 0.5f

        /** Tick gap for the nearest thing ahead; null when nothing is close enough to matter. */
        fun beepIntervalMs(nearestMetres: Float?): Long? {
            val metres = nearestMetres ?: return null
            if (metres > RANGE_M) return null
            val part = ((metres - NEAREST_M) / (RANGE_M - NEAREST_M)).coerceIn(0f, 1f)
            return (NEAREST_MS + (FARTHEST_MS - NEAREST_MS) * part).roundToLong()
        }
    }
}

enum class TrafficLightColor {
    RED,
    YELLOW,
    GREEN;

    companion object {
        private const val MIN_PIXELS = 10

        /** The colour with the most pixels, or null when the light is too small or too dim. */
        fun of(red: Int, yellow: Int, green: Int): TrafficLightColor? {
            val best = listOf(RED to red, YELLOW to yellow, GREEN to green).maxByOrNull { it.second }!!
            return if (best.second < MIN_PIXELS) null else best.first
        }
    }
}

/** Points to a saved place with a distance and a clock direction; no maps and no internet. */
object Beacon {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return (2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))).toFloat()
    }

    /** Compass bearing from the first point to the second, 0 = north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** Clock face direction, with 12 straight ahead of the way the phone points. */
    fun clockHour(bearingDeg: Float, headingDeg: Float): Int {
        val delta = ((bearingDeg - headingDeg) % 360f + 360f) % 360f
        val hour = (delta / 30f).roundToInt() % 12
        return if (hour == 0) 12 else hour
    }

    fun phrase(name: String, metres: Float, hour: Int): String =
        "$name, ${metres.roundToInt()} metres, $hour o'clock."
}
