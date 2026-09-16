package com.classroomscanner.settings

import android.content.Context
import com.classroomscanner.core.ScanSettings

/** Keeps the last Scan settings choices on the phone. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): ScanSettings {
        val d = ScanSettings()
        return ScanSettings(
            camera = enumOrDefault(prefs.getString(KEY_CAMERA, null), d.camera),
            compute = enumOrDefault(prefs.getString(KEY_COMPUTE, null), d.compute),
            model = enumOrDefault(prefs.getString(KEY_MODEL, null), d.model),
            minScore = prefs.getFloat(KEY_MIN_SCORE, d.minScore),
            speechOn = prefs.getBoolean(KEY_SPEECH, d.speechOn),
            colorsOn = prefs.getBoolean(KEY_COLORS, d.colorsOn),
        ).normalized()
    }

    fun save(settings: ScanSettings) {
        val s = settings.normalized()
        prefs.edit()
            .putString(KEY_CAMERA, s.camera.name)
            .putString(KEY_COMPUTE, s.compute.name)
            .putString(KEY_MODEL, s.model.name)
            .putFloat(KEY_MIN_SCORE, s.minScore)
            .putBoolean(KEY_SPEECH, s.speechOn)
            .putBoolean(KEY_COLORS, s.colorsOn)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val FILE = "scan_settings"
        const val KEY_CAMERA = "camera"
        const val KEY_COMPUTE = "compute"
        const val KEY_MODEL = "model"
        const val KEY_MIN_SCORE = "min_score"
        const val KEY_SPEECH = "speech_on"
        const val KEY_COLORS = "colors_on"
    }
}
