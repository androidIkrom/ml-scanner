package com.classroomscanner.core

/** What the app says about one object group. [angle] is scan-relative degrees. */
data class ObjectSummary(val label: String, val count: Int, val color: String?, val angle: Float)

object SummaryBuilder {

    private val irregular = mapOf(
        "person" to "people",
        "mouse" to "mice",
        "knife" to "knives",
        "sheep" to "sheep",
        "skis" to "skis",
        "scissors" to "scissors",
    )

    fun plural(label: String): String {
        val lastSpace = label.lastIndexOf(' ')
        val head = label.substring(0, lastSpace + 1)
        val word = label.substring(lastSpace + 1)
        val pluralWord = irregular[word] ?: when {
            word.endsWith("s") || word.endsWith("x") || word.endsWith("ch") || word.endsWith("sh") -> word + "es"
            word.length > 1 && word.endsWith("y") && word[word.length - 2] !in "aeiou" -> word.dropLast(1) + "ies"
            else -> word + "s"
        }
        return head + pluralWord
    }

    fun describe(o: ObjectSummary): String {
        val colorPart = o.color?.let { "$it " } ?: ""
        return if (o.count == 1) {
            val phrase = colorPart + o.label
            val article = if (phrase.first().lowercaseChar() in "aeiou") "an" else "a"
            "$article $phrase"
        } else {
            "${o.count} $colorPart${plural(o.label)}"
        }
    }

    fun fullSummary(objects: List<ObjectSummary>, coveragePercent: Int): String {
        val prefix = if (coveragePercent < 100) "I scanned $coveragePercent percent of the room. " else ""
        if (objects.isEmpty()) return prefix + "No objects found. Try better lighting and turn slowly."

        val parts = Sector4.entries.mapNotNull { sector ->
            val inSector = objects.filter { AngleMath.sector4(it.angle) == sector }
            if (inSector.isEmpty()) return@mapNotNull null
            val merged = inSector
                .groupBy { it.label to it.color }
                .map { (key, group) -> ObjectSummary(key.first, group.sumOf { it.count }, key.second, group.first().angle) }
                .sortedByDescending { it.count }
            merged.joinToString(", ") { describe(it) } + " " + sector.phrase
        }
        return prefix + "Around you: " + parts.joinToString("; ") + "."
    }

    fun livePhrase(o: ObjectSummary): String {
        val phrase = (o.color?.let { "$it " } ?: "") + o.label
        return phrase.replaceFirstChar { it.uppercaseChar() } + " " + AngleMath.sector4(o.angle).phrase + "."
    }
}
