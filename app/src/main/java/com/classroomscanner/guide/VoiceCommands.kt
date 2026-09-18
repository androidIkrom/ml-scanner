package com.classroomscanner.guide

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.classroomscanner.core.VoiceCommand
import com.classroomscanner.speech.SpeechInput

/** A screen that handles some spoken commands itself; return false to let the app handle them. */
interface VoiceCommandTarget {
    fun onVoiceCommand(command: VoiceCommand): Boolean
}

/**
 * Keeps the microphone listening until it is switched off: after every result or error it starts a
 * new turn, with a pause so the app's own speech is not heard as a command. Main thread only.
 */
class VoiceListener(
    context: Context,
    private val onText: (String) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var input: SpeechInput? = null

    var listening: Boolean = false
        private set

    val available: Boolean get() = SpeechInput.isAvailable(appContext)

    fun start() {
        if (listening) return
        listening = true
        again(FIRST_DELAY_MS)
    }

    fun stop() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        input?.cancel()
    }

    fun release() {
        stop()
        input?.destroy()
        input = null
    }

    private fun again(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (listening) listenOnce() }, delayMs)
    }

    private fun listenOnce() {
        val speech = input ?: SpeechInput(appContext).also { input = it }
        speech.listen(
            onListening = {},
            onResult = { text ->
                if (listening) {
                    onText(text)
                    again(AFTER_COMMAND_MS)
                }
            },
            onError = { message ->
                if (!listening) return@listen
                if (message.contains("Microphone")) {
                    onProblem(message)
                    stop()
                } else {
                    again(AFTER_ERROR_MS)
                }
            },
        )
    }

    private companion object {
        const val FIRST_DELAY_MS = 1_500L

        /** Long enough for a short answer to be spoken before the microphone opens again. */
        const val AFTER_COMMAND_MS = 2_500L
        const val AFTER_ERROR_MS = 600L
    }
}
