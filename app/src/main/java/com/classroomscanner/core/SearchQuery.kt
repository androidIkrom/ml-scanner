package com.classroomscanner.core

import kotlin.math.min

/** The 80 COCO classes the EfficientDet models report. */
object CocoLabels {
    val ALL: Set<String> = setOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog",
        "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
        "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
        "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
        "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant",
        "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors",
        "teddy bear", "hair drier", "toothbrush",
    )
}

enum class SavedKind { PERSON, CAR, OBJECT }

/** A saved person or item as the search sees it; [label] is null for people. */
data class SavedName(val kind: SavedKind, val id: Long, val name: String, val label: String?)

sealed interface SearchTarget {
    data class Person(val id: Long, val name: String) : SearchTarget
    data class Item(val id: Long, val name: String, val label: String) : SearchTarget
    data class Label(val label: String) : SearchTarget
    data class Unknown(val text: String) : SearchTarget
}

/** Turns spoken or typed search text into something the search screen can look for. */
object SearchResolver {
    private val fillers = setOf(
        "find", "search", "where", "wheres", "is", "are", "look", "for", "locate", "show", "me",
        "my", "the", "a", "an", "please", "can", "you", "i", "want", "to",
    )

    private val synonyms = mapOf(
        "phone" to "cell phone", "mobile" to "cell phone", "smartphone" to "cell phone",
        "mobile phone" to "cell phone",
        "table" to "dining table", "desk" to "dining table",
        "sofa" to "couch",
        "television" to "tv", "monitor" to "tv", "screen" to "tv",
        "bag" to "backpack", "purse" to "handbag", "luggage" to "suitcase",
        "man" to "person", "men" to "person", "woman" to "person", "women" to "person",
        "people" to "person", "human" to "person", "boy" to "person", "girl" to "person",
        "child" to "person", "children" to "person", "kid" to "person", "teacher" to "person",
        "student" to "person",
        "bike" to "bicycle", "motorbike" to "motorcycle",
        "plant" to "potted plant", "flower" to "potted plant",
        "glass" to "wine glass", "mug" to "cup",
        "computer" to "laptop", "notebook" to "laptop",
        "fridge" to "refrigerator", "plane" to "airplane", "seat" to "chair",
        "puppy" to "dog", "kitten" to "cat", "ball" to "sports ball",
        "remote control" to "remote", "hair dryer" to "hair drier",
    )

    /** Lowercase words without punctuation or command words. */
    fun clean(text: String): String =
        text.lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .filter { it.isNotEmpty() && it !in fillers }
            .joinToString(" ")

    fun resolve(text: String, saved: List<SavedName>): SearchTarget {
        val query = clean(text)
        if (query.isEmpty()) return SearchTarget.Unknown("")
        findSaved(query, saved)?.let { return it.toTarget() }
        findLabel(query)?.let { return SearchTarget.Label(it) }
        return SearchTarget.Unknown(query)
    }

    private fun findSaved(query: String, saved: List<SavedName>): SavedName? {
        val named = saved.map { it to clean(it.name) }.filter { it.second.isNotEmpty() }
        named.firstOrNull { it.second == query }?.let { return it.first }
        named.filter { " $query ".contains(" ${it.second} ") }
            .maxByOrNull { it.second.length }?.let { return it.first }
        return named
            .filter { it.second.length >= MIN_FUZZY_LENGTH }
            .map { it to levenshtein(it.second, query) }
            .filter { it.second <= MAX_TYPOS }
            .minByOrNull { it.second }
            ?.first?.first
    }

    private fun findLabel(query: String): String? {
        val words = query.split(' ')
        val phrases = buildList {
            add(query)
            for (i in 0 until words.size - 1) add(words[i] + " " + words[i + 1])
            addAll(words)
        }
        for (phrase in phrases) {
            for (form in singulars(phrase)) {
                val label = synonyms[form] ?: form
                if (label in CocoLabels.ALL) return label
            }
        }
        return null
    }

    private fun singulars(word: String): List<String> = buildList {
        add(word)
        if (word.endsWith("ies")) add(word.dropLast(3) + "y")
        if (word.endsWith("es")) add(word.dropLast(2))
        if (word.endsWith("s")) add(word.dropLast(1))
    }

    private fun SavedName.toTarget(): SearchTarget = when (kind) {
        SavedKind.PERSON -> SearchTarget.Person(id, name)
        SavedKind.CAR, SavedKind.OBJECT -> SearchTarget.Item(id, name, label.orEmpty())
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private const val MIN_FUZZY_LENGTH = 4
    private const val MAX_TYPOS = 2
}
