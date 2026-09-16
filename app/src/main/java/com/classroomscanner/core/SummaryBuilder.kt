package com.classroomscanner.core

/** What the app says about one object group. [angle] is scan-relative degrees. */
data class ObjectSummary(
    val label: String,
    val count: Int,
    val color: String?,
    val angle: Float,
    /** True when [label] is a recognized person's name rather than an object class. */
    val isName: Boolean = false,
)

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
        if (o.isName) return o.label
        val colorPart = colorOf(o)?.let { "$it " } ?: ""
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
            // Known people first (in the order given), then object groups, largest first.
            val groups = inSector
                .groupBy { it.label to it.isName }
                .map { (_, group) -> group }
                .sortedWith(compareBy({ !it.first().isName }, { group -> -group.sumOf { it.count } }))
            joinGroups(groups.map { describeGroup(it) }) + " " + sector.phrase
        }
        return prefix + "Around you: " + parts.joinToString("; ") + "."
    }

    /**
     * Same-label objects in one direction are spoken as one group: "3 blue chairs" when they share a color,
     * otherwise "4 chairs in blue, red and gray" listing the known colors, most common first.
     */
    private fun describeGroup(group: List<ObjectSummary>): String {
        val first = group.first()
        if (first.isName) return first.label
        val count = group.sumOf { it.count }
        val colorCounts = LinkedHashMap<String, Int>()
        for (o in group) colorOf(o)?.let { colorCounts[it] = (colorCounts[it] ?: 0) + o.count }
        val allSameKnownColor = colorCounts.size == 1 && group.all { colorOf(it) != null }
        if (colorCounts.isEmpty() || allSameKnownColor) {
            return describe(ObjectSummary(first.label, count, colorCounts.keys.firstOrNull(), first.angle))
        }
        val colors = colorCounts.entries.sortedByDescending { it.value }.map { it.key }
        return describe(ObjectSummary(first.label, count, null, first.angle)) + " in " + joinWithAnd(colors)
    }

    private fun joinWithAnd(items: List<String>): String =
        if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + " and " + items.last()

    /** "A and B", "A, B and C"; ", and" when a part already contains "and" (e.g. a color list). */
    private fun joinGroups(parts: List<String>): String {
        if (parts.size == 1) return parts[0]
        val separator = if (parts.any { " and " in it }) ", and " else " and "
        return parts.dropLast(1).joinToString(", ") + separator + parts.last()
    }

    private fun colorOf(o: ObjectSummary): String? =
        o.color?.takeIf { !o.isName && ColorPolicy.hasColor(o.label) }

    fun livePhrase(o: ObjectSummary): String {
        val phrase = (colorOf(o)?.let { "$it " } ?: "") + o.label
        return phrase.replaceFirstChar { it.uppercaseChar() } + " " + AngleMath.sector4(o.angle).phrase + "."
    }
}
