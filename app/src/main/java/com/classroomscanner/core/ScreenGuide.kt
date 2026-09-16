package com.classroomscanner.core

/** A pressable thing on screen: its spoken description and its center in screen pixels. */
data class GuideItem(val label: String, val centerX: Float, val centerY: Float)

enum class ControlKind { BUTTON, SWITCH, TOGGLE, SLIDER, OTHER }

/** Words for the voice guide: what a control is and where it sits on the screen. */
object ScreenGuide {
    private const val MAX_ITEMS = 12
    private val rows = listOf("top", "", "bottom")
    private val cols = listOf("left", "", "right")

    /** Position on a 3 x 3 grid, e.g. "top left", "top", "center", "bottom right". */
    fun position(centerX: Float, centerY: Float, width: Float, height: Float): String {
        val row = rows[third(centerY, height)]
        val col = cols[third(centerX, width)]
        return listOf(row, col).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "center" }
    }

    fun describeControl(label: String, kind: ControlKind, state: String? = null, enabled: Boolean = true): String {
        val name = if (label == NAVIGATE_UP) "Back" else label
        val text = when (kind) {
            ControlKind.BUTTON -> "$name button"
            ControlKind.SWITCH -> "$name switch" + state.suffix()
            ControlKind.TOGGLE -> name + state.suffix()
            ControlKind.SLIDER -> "$name slider" + state.suffix()
            ControlKind.OTHER -> name
        }
        return if (enabled) text else "$text, unavailable"
    }

    /** Everything that can be pressed, read top to bottom and left to right. */
    fun summary(items: List<GuideItem>, width: Float, height: Float): String {
        if (items.isEmpty()) return "Nothing to press on this screen."
        val ordered = items.sortedWith(compareBy({ third(it.centerY, height) }, { it.centerX }))
        val spoken = ordered.take(MAX_ITEMS).joinToString(". ") {
            "${it.label}, ${position(it.centerX, it.centerY, width, height)}"
        }
        val rest = ordered.size - MAX_ITEMS
        return "This screen has: $spoken." + if (rest > 0) " And $rest more." else ""
    }

    private fun third(value: Float, size: Float): Int =
        if (size <= 0f) 1 else (value / size * 3).toInt().coerceIn(0, 2)

    private fun String?.suffix() = if (this.isNullOrEmpty()) "" else ", $this"

    /** Content description AndroidX Navigation gives the toolbar's up arrow. */
    private const val NAVIGATE_UP = "Navigate up"
}
