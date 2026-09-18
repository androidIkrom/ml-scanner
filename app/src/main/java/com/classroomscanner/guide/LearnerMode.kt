package com.classroomscanner.guide

import android.content.Context
import com.classroomscanner.R
import com.classroomscanner.core.ScreenHelp

/**
 * Learner mode: when it is on, every screen says in one or two sentences what it is for as soon as
 * it opens. The same texts answer questions like "what is search".
 */
class LearnerMode(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            field = value
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** The help text of a navigation destination, or null when that screen has none. */
    fun helpFor(destinationId: Int): String? {
        val key = keyOf(destinationId) ?: return null
        return ScreenHelp.describe(key)
    }

    private fun keyOf(destinationId: Int): String? = when (destinationId) {
        R.id.home_fragment -> ScreenHelp.HOME
        R.id.scan_hub_fragment -> ScreenHelp.SCAN_HUB
        R.id.settings_fragment -> ScreenHelp.SETTINGS
        R.id.permissions_fragment -> ScreenHelp.PERMISSION
        R.id.camera_fragment -> ScreenHelp.SCANNER
        R.id.history_fragment -> ScreenHelp.HISTORY
        R.id.saved_fragment -> ScreenHelp.SAVED
        R.id.person_fragment -> ScreenHelp.PERSON
        R.id.add_person_fragment -> ScreenHelp.ADD_PERSON
        R.id.enroll_fragment -> ScreenHelp.FACE_SCAN
        R.id.item_fragment -> ScreenHelp.ITEM
        R.id.add_item_fragment -> ScreenHelp.ADD_ITEM
        R.id.item_enroll_fragment -> ScreenHelp.ITEM_SCAN
        R.id.walk_fragment -> ScreenHelp.WALK
        R.id.search_fragment -> ScreenHelp.SEARCH
        R.id.search_camera_fragment -> ScreenHelp.SEARCHING
        else -> null
    }

    private companion object {
        const val PREFS = "app_prefs"
        const val KEY_ENABLED = "learner_enabled"
    }
}
