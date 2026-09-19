package com.classroomscanner.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** One stored face embedding of a saved person. */
class KnownFace(val personId: Long, val name: String, val vector: FloatArray)

data class FaceMatch(val personId: Long, val name: String, val similarity: Float)

/**
 * Compares FaceNet embeddings by cosine similarity, carefully enough not to mix people up: a person
 * is scored by their few best samples together, not by one lucky sample, and a name is only given
 * when it clearly beats the next person.
 */
object FaceMatcher {
    /**
     * A person's score must reach this. The reference app used 0.3, which lets strangers through;
     * with aligned faces the same person scores well above 0.5.
     */
    const val THRESHOLD = 0.5f

    /** The winner must beat the next person by this much, or the face is left unnamed. */
    const val MARGIN = 0.08f

    /** How many of a person's best samples are averaged into their score. */
    private const val TOP_SAMPLES = 3

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    fun bestMatch(vector: FloatArray, known: List<KnownFace>, threshold: Float = THRESHOLD): FaceMatch? {
        val scores = known.groupBy { it.personId }.map { (id, faces) ->
            val best = faces.map { cosine(vector, it.vector) }.sortedDescending().take(TOP_SAMPLES)
            FaceMatch(id, faces.first().name, best.average().toFloat())
        }.sortedByDescending { it.similarity }
        val winner = scores.firstOrNull() ?: return null
        if (winner.similarity < threshold) return null
        val runnerUp = scores.getOrNull(1)?.similarity ?: return winner
        return if (winner.similarity - runnerUp >= MARGIN) winner else null
    }
}

/** Which faces are worth comparing at all: small or turned-away faces give unreliable embeddings. */
object FaceQuality {
    private const val MIN_SIZE_PX = 64
    private const val MAX_YAW_DEG = 35f
    private const val MAX_PITCH_DEG = 25f

    fun usable(sizePx: Int, yawDeg: Float, pitchDeg: Float): Boolean =
        sizePx >= MIN_SIZE_PX && abs(yawDeg) <= MAX_YAW_DEG && abs(pitchDeg) <= MAX_PITCH_DEG
}

/** FaceNet input normalization: per-image standardization, as in the reference app. */
object FaceNetPreprocess {
    fun standardize(pixels: FloatArray): FloatArray {
        if (pixels.isEmpty()) return pixels
        val mean = pixels.average().toFloat()
        var sumSq = 0.0
        for (p in pixels) sumSq += (p - mean) * (p - mean)
        val std = max(sqrt(sumSq / pixels.size).toFloat(), 1f / sqrt(pixels.size.toFloat()))
        return FloatArray(pixels.size) { (pixels[it] - mean) / std }
    }
}

enum class Pose(val instruction: String) {
    STRAIGHT("Look straight at the camera."),
    LEFT("Turn your head to your left."),
    RIGHT("Turn your head to your right."),
    UP("Tilt your head up."),
    DOWN("Tilt your head down."),
}

/**
 * Walks a person through the poses needed to enroll their face.
 * [offer] takes ML Kit head angles in degrees (yaw = Euler Y, pitch = Euler X).
 */
class EnrollmentGuide(private val samplesPerPose: Int = 4) {
    private var poseIndex = 0
    private var samplesInPose = 0

    val total: Int get() = Pose.entries.size * samplesPerPose
    val captured: Int get() = poseIndex * samplesPerPose + samplesInPose
    val done: Boolean get() = poseIndex >= Pose.entries.size
    val currentPose: Pose? get() = Pose.entries.getOrNull(poseIndex)

    /** True right after a sample completed a pose (time to speak the next instruction). */
    var poseFinished: Boolean = false
        private set

    fun percent(): Int = captured * 100 / total

    /** Returns true when the angles match the current pose and the sample was counted. */
    fun offer(yaw: Float, pitch: Float): Boolean {
        poseFinished = false
        val pose = currentPose ?: return false
        if (poseOf(yaw, pitch) != pose) return false
        samplesInPose++
        if (samplesInPose == samplesPerPose) {
            poseIndex++
            samplesInPose = 0
            poseFinished = true
        }
        return true
    }

    companion object {
        fun poseOf(yaw: Float, pitch: Float): Pose? = when {
            abs(yaw) < 10f && abs(pitch) < 10f -> Pose.STRAIGHT
            yaw > 20f && abs(pitch) < 15f -> Pose.LEFT
            yaw < -20f && abs(pitch) < 15f -> Pose.RIGHT
            pitch > 12f && abs(yaw) < 15f -> Pose.UP
            pitch < -12f && abs(yaw) < 15f -> Pose.DOWN
            else -> null
        }
    }
}

/** Keeps a recognized person from also being reported as an unnamed "person". */
object NamedPeople {
    private const val PERSON = "person"

    fun dropShadowedPersons(objects: List<ObjectSummary>, mergeDeg: Float = 20f): List<ObjectSummary> {
        val named = objects.filter { it.isName }
        return objects.mapNotNull { o ->
            if (o.isName || o.label != PERSON) return@mapNotNull o
            val nearby = named.filter { abs(AngleMath.diff(it.angle, o.angle)) < mergeDeg }.sumOf { it.count }
            val rest = o.count - nearby
            if (rest > 0) o.copy(count = rest) else null
        }
    }
}
