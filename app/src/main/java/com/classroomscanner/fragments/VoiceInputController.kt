package com.classroomscanner.fragments

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.classroomscanner.MainActivity
import com.classroomscanner.R
import com.classroomscanner.speech.SpeechInput

/**
 * Microphone button logic for one fragment: asks for the permission, silences speech, listens once.
 * Create it as a fragment field (it registers a permission launcher) and call [release] in onDestroyView.
 */
class VoiceInputController(
    private val fragment: Fragment,
    private val onText: (String) -> Unit,
) {
    var onListening: ((Boolean) -> Unit)? = null
    private var input: SpeechInput? = null

    private val permission =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else fragment.speak(fragment.getString(R.string.mic_permission_needed))
        }

    val available: Boolean
        get() = fragment.context?.let { SpeechInput.isAvailable(it) } ?: false

    fun listen() {
        val context = fragment.context ?: return
        if (!available) {
            fragment.speak(fragment.getString(R.string.voice_input_unavailable))
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            start()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun release() {
        input?.destroy()
        input = null
        onListening?.invoke(false)
    }

    private fun start() {
        val context = fragment.context ?: return
        (fragment.activity as? MainActivity)?.voiceGuide?.stopSpeaking()
        val speech = input ?: SpeechInput(context).also { input = it }
        speech.listen(
            onListening = { onListening?.invoke(it) },
            onResult = { if (fragment.view != null) onText(it) },
            onError = { if (fragment.view != null) fragment.speak(it) },
        )
    }
}
