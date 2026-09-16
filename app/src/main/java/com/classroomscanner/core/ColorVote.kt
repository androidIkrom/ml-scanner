package com.classroomscanner.core

/** Names an object's color by letting every sampled pixel vote, so shadows cannot hide a colorful object. */
object ColorVote {
    /** Share of named pixels that must be colorful before a colorful name wins over black/white/gray. */
    const val MIN_CHROMATIC_SHARE = 0.3f

    private val achromatic = setOf("black", "white", "gray")

    /** Names [pixels] (packed 0xRRGGBB) after white balance [gains]; null when nothing could be named. */
    fun nameOfPixels(pixels: IntArray, gains: WhiteBalance.Gains): String? =
        pick(
            pixels.map { p ->
                val hsv = rgbToHsv(WhiteBalance.apply(p, gains))
                ColorMapper.nameFromHsv(hsv[0], hsv[1], hsv[2])
            }
        )

    fun pick(names: List<String?>): String? {
        val counts = names.filterNotNull().groupingBy { it }.eachCount()
        val total = counts.values.sum()
        if (total == 0) return null
        val chromatic = counts.filterKeys { it !in achromatic }
        val chromaticShare = chromatic.values.sum().toFloat() / total
        val pool = if (chromatic.isNotEmpty() && chromaticShare >= MIN_CHROMATIC_SHARE) {
            chromatic
        } else {
            counts.filterKeys { it in achromatic }
        }
        return pool.maxByOrNull { it.value }?.key
    }

    /** Hue in 0..360, saturation and value in 0..1. */
    fun rgbToHsv(rgb: Int): FloatArray {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        val s = if (max == 0f) 0f else d / max
        return floatArrayOf(AngleMath.normalize(h), s, max)
    }
}

/** Which labels get a color at all. People are described without one. */
object ColorPolicy {
    private val colorless = setOf("person")

    fun hasColor(label: String): Boolean = label !in colorless
}
