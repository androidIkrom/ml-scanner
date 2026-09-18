package com.classroomscanner.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One-shot English speech-to-text with Android's recognizer. Main thread only. */
class SpeechInput(context: Context) {
    private val recognizer = create(context.applicationContext)
    private var destroyed = false

    fun listen(onListening: (Boolean) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (destroyed) return
        recognizer.cancel()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListening(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                onListening(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) onError(NOT_CAUGHT) else onResult(text)
            }

            override fun onError(error: Int) {
                onListening(false)
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NOT_CAUGHT
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is needed."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Voice input needs the offline English speech pack or internet."
                        else -> "Voice input failed. Try again."
                    }
                )
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Waiting quietly is normal: the user may press the button and speak seconds later.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_LISTEN_MS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        recognizer.startListening(intent)
    }

    fun cancel() {
        if (!destroyed) recognizer.cancel()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        recognizer.destroy()
    }

    companion object {
        private const val MIN_LISTEN_MS = 8_000
        private const val SILENCE_MS = 2_000
        private const val NOT_CAUGHT = "I didn't catch that. Try again."

        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context) || hasOnDevice(context)

        /** Some phones have no recognition service app but do have Android's on-device recognizer. */
        private fun hasOnDevice(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        private fun create(context: Context): SpeechRecognizer =
            if (!SpeechRecognizer.isRecognitionAvailable(context) && hasOnDevice(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
    }
}
