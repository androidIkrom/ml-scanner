package com.classroomscanner.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Throttled English TTS. Call from the main thread. */
class SpeechAnnouncer(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<String>()
    private var nextAllowedAt = 0L

    var available: Boolean = false
        private set

    /** When true nothing is spoken; callers still show the text on screen. */
    var muted: Boolean = false

    private var isShutDown = false

    override fun onInit(status: Int) {
        available = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
    }

    /** Queues a short announcement; stale ones are dropped when the queue is long. */
    fun announce(text: String) {
        if (!available || muted) return
        pending.addLast(text)
        while (pending.size > MAX_PENDING) pending.removeFirst()
        pump()
    }

    /** Clears the queue and speaks [text] immediately (final summaries, history replay). */
    fun speakNow(text: String) {
        if (!available || muted) return
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "summary")
        nextAllowedAt = SystemClock.uptimeMillis() + GAP_MS
    }

    /** Silences speech right away (before listening to the microphone). */
    fun stop() {
        if (isShutDown) return
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        tts.stop()
    }

    fun shutdown() {
        if (isShutDown) return
        isShutDown = true
        handler.removeCallbacksAndMessages(null)
        tts.stop()
        tts.shutdown()
    }

    /** Lets a summary already being spoken finish, then shuts down; otherwise shuts down now. */
    fun shutdownWhenIdle() {
        if (isShutDown) return
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        if (tts.isSpeaking) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    handler.post { shutdown() }
                }

                override fun onError(utteranceId: String?) {
                    handler.post { shutdown() }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    handler.post { shutdown() }
                }
            })
            handler.postDelayed({ shutdown() }, SHUTDOWN_SAFETY_MS)
        } else {
            shutdown()
        }
    }

    private fun pump() {
        handler.removeCallbacksAndMessages(null)
        if (pending.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (tts.isSpeaking || now < nextAllowedAt) {
            handler.postDelayed(::pump, POLL_MS)
            return
        }
        tts.speak(pending.removeFirst(), TextToSpeech.QUEUE_ADD, null, "live")
        nextAllowedAt = now + GAP_MS
        if (pending.isNotEmpty()) handler.postDelayed(::pump, POLL_MS)
    }

    private companion object {
        const val GAP_MS = 1_500L
        const val POLL_MS = 250L
        const val MAX_PENDING = 3
        const val SHUTDOWN_SAFETY_MS = 15_000L
    }
}
