package com.classroomscanner.guide

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.TextView
import com.classroomscanner.R
import com.classroomscanner.core.ControlKind
import com.classroomscanner.core.GuideItem
import com.classroomscanner.core.ScreenGuide
import com.classroomscanner.speech.SpeechAnnouncer
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import java.util.Locale
import kotlin.math.abs

/**
 * Speaks the control under a tap; a tap on empty space reads every control on the screen with its position.
 * Feed it every touch of a window through [onTouch]. Main thread only.
 */
class VoiceGuide(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val speech = SpeechAnnouncer(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    var enabled: Boolean = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            field = value
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** Speaks [text] even when the guide is off (used by the on/off button itself). */
    fun say(text: String) = speech.speakNow(text)

    fun onTouch(root: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                val isTap = abs(event.rawX - downX) <= touchSlop && abs(event.rawY - downY) <= touchSlop
                if (isTap) onTap(root, event.rawX.toInt(), event.rawY.toInt())
            }
        }
    }

    fun shutdown() = speech.shutdown()

    private fun onTap(root: View, x: Int, y: Int) {
        val target = findControl(root, x, y)
        if (!enabled || target?.id == R.id.voice_guide_toggle) return
        val text = if (target != null) {
            describe(target)
        } else {
            val items = mutableListOf<GuideItem>()
            collect(root, items)
            val screen = root.rootView
            ScreenGuide.summary(items, screen.width.toFloat(), screen.height.toFloat())
        }
        text?.let { speech.speakNow(it) }
    }

    /** Topmost control containing the point, searching the children drawn last first. */
    private fun findControl(view: View, x: Int, y: Int): View? {
        if (!view.isShown || !contains(view, x, y)) return null
        if (isControl(view)) return view
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                findControl(view.getChildAt(i), x, y)?.let { return it }
            }
        }
        return null
    }

    private fun collect(view: View, out: MutableList<GuideItem>) {
        if (!view.isShown) return
        if (isControl(view)) {
            val text = describe(view) ?: return
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            out += GuideItem(text, loc[0] + view.width / 2f, loc[1] + view.height / 2f)
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
    }

    private fun isControl(view: View): Boolean =
        (view.isClickable || view is Slider || view is CompoundButton) && labelOf(view) != null

    private fun describe(view: View): String? {
        val label = labelOf(view) ?: return null
        return when {
            view.id == R.id.voice_guide_toggle ->
                ScreenGuide.describeControl(VOICE_GUIDE, ControlKind.TOGGLE, if (enabled) "on" else "off")
            view is Slider -> ScreenGuide.describeControl(
                label, ControlKind.SLIDER, String.format(Locale.US, "%.1f", view.value), view.isEnabled
            )
            view is CompoundButton -> ScreenGuide.describeControl(
                label, ControlKind.SWITCH, if (view.isChecked) "on" else "off", view.isEnabled
            )
            view is MaterialButton && view.isCheckable -> ScreenGuide.describeControl(
                label, ControlKind.TOGGLE, if (view.isChecked) "selected" else "not selected", view.isEnabled
            )
            view is Button || view is ImageButton ->
                ScreenGuide.describeControl(label, ControlKind.BUTTON, enabled = view.isEnabled)
            else -> ScreenGuide.describeControl(label, ControlKind.OTHER, enabled = view.isEnabled)
        }
    }

    private fun labelOf(view: View): String? =
        view.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
            ?: (view as? ViewGroup)?.let { firstText(it) }

    private fun firstText(group: ViewGroup): String? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (!child.isShown) continue
            val text = (child as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
                ?: (child as? ViewGroup)?.let { firstText(it) }
            if (text != null) return text
        }
        return null
    }

    private fun contains(view: View, x: Int, y: Int): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return x >= loc[0] && x < loc[0] + view.width && y >= loc[1] && y < loc[1] + view.height
    }

    private companion object {
        const val PREFS = "app_prefs"
        const val KEY_ENABLED = "voice_guide_enabled"
        const val VOICE_GUIDE = "Voice guide"
    }
}
