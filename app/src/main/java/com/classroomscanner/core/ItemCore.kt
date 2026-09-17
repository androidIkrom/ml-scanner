package com.classroomscanner.core

/** What a saved item is; decides which detector labels it may be enrolled and matched as. */
enum class ItemKind {
    CAR,
    OBJECT;

    fun allows(label: String): Boolean = when (this) {
        CAR -> label in VEHICLES
        OBJECT -> label != PERSON
    }

    private companion object {
        const val PERSON = "person"
        val VEHICLES = setOf("car", "truck", "bus", "motorcycle")
    }
}

/** One stored image embedding of a saved car or object. */
class KnownItem(val itemId: Long, val name: String, val label: String, val vector: FloatArray)

data class ItemMatch(val itemId: Long, val name: String, val similarity: Float)

/** Compares MediaPipe image embeddings of detections with saved items of the same label. */
object ItemMatcher {
    /** Starting value for mobilenet_v3_small embeddings; tune on the device. */
    const val THRESHOLD = 0.75f

    /** Best item above [threshold]; a null [label] compares items of every label. */
    fun bestMatch(
        vector: FloatArray,
        label: String?,
        known: List<KnownItem>,
        threshold: Float = THRESHOLD,
    ): ItemMatch? =
        known.asSequence()
            .filter { label == null || it.label == label }
            .map { ItemMatch(it.itemId, it.name, FaceMatcher.cosine(vector, it.vector)) }
            .filter { it.similarity >= threshold }
            .maxByOrNull { it.similarity }

    /** The label seen most often while enrolling (the detector may flip between similar classes). */
    fun mostCommon(labels: List<String>): String? =
        labels.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

enum class ItemStep(val instruction: String) {
    STILL("Hold the camera still."),
    LEFT("Move a little to the left."),
    RIGHT("Move a little to the right."),
}

/** Counts samples while the user views an item from three slightly different spots. */
class ItemEnrollmentGuide(private val samplesPerStep: Int = 4) {
    private var stepIndex = 0
    private var samplesInStep = 0

    val total: Int get() = ItemStep.entries.size * samplesPerStep
    val captured: Int get() = stepIndex * samplesPerStep + samplesInStep
    val done: Boolean get() = stepIndex >= ItemStep.entries.size
    val currentStep: ItemStep? get() = ItemStep.entries.getOrNull(stepIndex)

    /** True right after a sample completed a step (time to speak the next instruction). */
    var stepFinished: Boolean = false
        private set

    fun percent(): Int = captured * 100 / total

    /** Counts one sample; false once all steps are done. */
    fun offer(): Boolean {
        stepFinished = false
        if (done) return false
        samplesInStep++
        if (samplesInStep == samplesPerStep) {
            stepIndex++
            samplesInStep = 0
            stepFinished = true
        }
        return true
    }
}

/** A detection box in normalized upright coordinates. */
data class Candidate(val centerX: Float, val centerY: Float, val area: Float)

/** Picks the detection the user is pointing the camera at. */
object CenterPick {
    fun pick(candidates: List<Candidate>, minArea: Float): Int? =
        candidates.indices
            .filter { candidates[it].area >= minArea }
            .minByOrNull {
                val dx = candidates[it].centerX - 0.5f
                val dy = candidates[it].centerY - 0.5f
                dx * dx + dy * dy
            }
}
