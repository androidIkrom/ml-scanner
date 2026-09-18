package com.classroomscanner.core

import kotlin.math.atan
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
        "person", "bicycle", "car", "motorcycle", "bus", "truck", "dog",
        "bench", "chair", "couch", "dining table", "potted plant",
        "stop sign", "fire hydrant", "suitcase",
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

    /** What to say about the floor ahead, or null when it is flat. Never tells the user to go. */
    fun ground(finding: GroundFinding, stepLength: Float?): String? {
        val what = when {
            finding.dropM != null && (finding.stepUpM == null || finding.dropM < finding.stepUpM) ->
                "Step down ahead" to finding.dropM
            finding.stepUpM != null -> (if (finding.stairs) "Stairs up ahead" else "Step up ahead") to finding.stepUpM
            else -> return null
        }
        val far = if (stepLength != null) {
            val steps = WalkGeometry.steps(what.second, stepLength)
            "${number(steps)} step${if (steps == 1) "" else "s"}"
        } else {
            "${what.second.roundToInt()} metres"
        }
        return "${what.first}, $far."
    }

    fun trafficLight(color: TrafficLightColor): String =
        "${color.name.lowercase().replaceFirstChar { it.uppercase() }} light seen."

    /** Small counts read better as words. */
    internal fun number(n: Int): String = when (n) {
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

/**
 * Walls, doors and everything else the detector has no class for: the depth image is read in the
 * band the walker's body passes through, and a zone counts as blocked only when enough of it is
 * close, so single stray pixels stay quiet.
 */
object DepthObstacles {
    /** The rows to look at, as fractions of the image: chest height, not the floor or the ceiling. */
    private const val BAND_TOP = 0.3f
    private const val BAND_BOTTOM = 0.62f

    private const val MIN_M = 0.3f
    private const val MAX_M = 8f

    /** A zone must have at least this share of close readings before it is called blocked. */
    private const val MIN_SHARE = 0.4f

    /** Metres to the nearest thing in each zone; a zone is missing when it is clear. */
    fun nearest(
        depthsM: FloatArray,
        width: Int,
        height: Int,
        limitM: Float = 3f,
    ): Map<WalkZone, Float> {
        val found = HashMap<WalkZone, Float>()
        val top = (height * BAND_TOP).toInt().coerceIn(0, height - 1)
        val bottom = (height * BAND_BOTTOM).toInt().coerceIn(top + 1, height)
        for (zone in WalkZone.entries) {
            val from = when (zone) {
                WalkZone.LEFT -> 0
                WalkZone.AHEAD -> width / 3
                WalkZone.RIGHT -> width * 2 / 3
            }
            val to = when (zone) {
                WalkZone.LEFT -> width / 3
                WalkZone.AHEAD -> width * 2 / 3
                WalkZone.RIGHT -> width
            }
            var valid = 0
            val close = ArrayList<Float>()
            for (y in top until bottom) {
                for (x in from until to) {
                    val metres = depthsM[y * width + x]
                    if (metres < MIN_M || metres > MAX_M) continue
                    valid++
                    if (metres <= limitM) close += metres
                }
            }
            if (valid == 0 || close.size < MIN_SHARE * valid) continue
            close.sort()
            // The lower quarter, so the nearest part of a wall decides, without trusting one pixel.
            found[zone] = close[close.size / 4]
        }
        return found
    }
}

/**
 * Keeps the detector's mistakes quiet: a thing is only announced after it has been seen in most of
 * the last frames.
 */
class HazardConfirmer(private val minHits: Int = 3, private val window: Int = 5) {
    private val seen = HashMap<String, ArrayDeque<Boolean>>()
    private val latest = HashMap<String, Hazard>()

    /** The hazards of this frame that have been seen often enough to be believed. */
    fun confirmed(hazards: List<Hazard>): List<Hazard> {
        val here = hazards.associateBy { it.label }
        for (label in (seen.keys + here.keys).toSet()) {
            val hits = seen.getOrPut(label) { ArrayDeque() }
            hits.addLast(label in here)
            while (hits.size > window) hits.removeFirst()
            if (hits.none { it }) seen.remove(label)
        }
        here.forEach { (label, hazard) -> latest[label] = hazard }
        return hazards.filter { (seen[it.label]?.count { hit -> hit } ?: 0) >= minHits }
    }

    fun clear() {
        seen.clear()
        latest.clear()
    }
}

/** What the floor ahead does: a rise, a fall, or several rises in a row. */
data class GroundFinding(val stepUpM: Float?, val dropM: Float?, val stairs: Boolean = false)

/**
 * Stairs, kerbs and drop-offs, from the depth image alone. For every sample the height above the
 * floor is worked out from how far the ray travelled and how steeply it points down: about zero is
 * floor, clearly higher is a step up, clearly lower is a step down. No model can do this on a
 * phone, but geometry can.
 */
object GroundProfile {
    /** Heights within this of the floor are just floor. */
    private const val FLAT_M = 0.09f

    /** Below this a rise is a kerb rather than a wall, and a fall is a step rather than a cliff. */
    private const val MAX_STEP_M = 0.8f

    /** How far ahead the floor is worth reading. */
    private const val MAX_AHEAD_M = 4f
    private const val MIN_AHEAD_M = 0.6f

    /** A band must hold this many samples before it counts, so noise stays quiet. */
    private const val MIN_SAMPLES = 3

    fun analyze(
        depthsM: FloatArray,
        width: Int,
        height: Int,
        focalPx: Float,
        horizonY: Float,
        cameraHeightM: Float,
    ): GroundFinding {
        val from = width / 3
        val to = width * 2 / 3
        // Distance ahead -> height above the floor, for the strip the walker is heading into.
        val rises = ArrayList<Float>()
        val falls = ArrayList<Float>()
        for (row in 0 until height) {
            val alpha = atan((row - horizonY) / focalPx)
            // Rays at or above the horizon never meet the floor.
            if (alpha <= 0.01f) continue
            var samples = 0
            var risingHits = 0
            var fallingHits = 0
            var forwardSum = 0f
            for (col in from until to) {
                val distance = depthsM[row * width + col]
                if (distance <= 0f) continue
                val heightAboveFloor = cameraHeightM - distance * sin(alpha)
                val forward = distance * cos(alpha)
                if (forward < MIN_AHEAD_M || forward > MAX_AHEAD_M) continue
                samples++
                forwardSum += forward
                when {
                    heightAboveFloor > FLAT_M && heightAboveFloor <= MAX_STEP_M -> risingHits++
                    heightAboveFloor < -FLAT_M && heightAboveFloor >= -MAX_STEP_M -> fallingHits++
                }
            }
            if (samples < MIN_SAMPLES) continue
            val forward = forwardSum / samples
            if (risingHits >= samples * 0.6f) rises += forward
            if (fallingHits >= samples * 0.6f) falls += forward
        }
        val stepUp = rises.minOrNull()
        val drop = falls.minOrNull()
        // Several rising bands, a hand's width apart, are a flight of stairs rather than one kerb.
        val stairs = rises.distinctBy { (it * 3).toInt() }.size >= 3
        return GroundFinding(stepUp, drop, stairs)
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
