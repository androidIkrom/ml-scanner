package com.classroomscanner.search

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat

/** Repeating beep whose speed follows the target, plus a short buzz. Main thread only. */
class Beeper(context: Context) {
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
    private val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var intervalMs: Long? = null
    private var released = false

    private val tick = object : Runnable {
        override fun run() {
            val interval = intervalMs ?: return
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            handler.postDelayed(this, interval)
        }
    }

    /** Null stops beeping; a value starts or retunes it. */
    fun setInterval(ms: Long?) {
        if (released) return
        val wasOff = intervalMs == null
        intervalMs = ms
        if (ms == null) {
            handler.removeCallbacks(tick)
        } else if (wasOff) {
            handler.post(tick)
        }
    }

    fun buzz() {
        if (released) return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(BUZZ_MS)
        }
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(tick)
        tone.release()
    }

    private companion object {
        const val VOLUME = 80
        const val BEEP_MS = 80
        const val BUZZ_MS = 60L
    }
}
