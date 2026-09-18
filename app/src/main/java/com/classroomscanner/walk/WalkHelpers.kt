package com.classroomscanner.walk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.classroomscanner.core.TrafficLightColor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable

private const val TAG = "ClassroomScanner"

/** Counts steps with the phone's step detector, so distances can be spoken in steps. */
class StepCounter(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    var steps: Int = 0
        private set

    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) steps += event.values[0].toInt()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Saved places for the beacon: a name and the spot the user stood on. */
class PlaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(name: String, latitude: Double, longitude: Double) {
        prefs.edit().putString(key(name), "$latitude,$longitude").apply()
    }

    /** Latitude and longitude of a saved place, or null when it is not saved. */
    fun place(name: String): Pair<Double, Double>? {
        val raw = prefs.getString(key(name), null) ?: return null
        val parts = raw.split(',')
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    fun names(): List<String> = prefs.all.keys.map { it.removePrefix(PREFIX) }.sorted()

    private fun key(name: String) = PREFIX + name.lowercase().trim()

    private companion object {
        const val PREFS = "places"
        const val PREFIX = "place_"
    }
}

/** Reads nearby signs with ML Kit; fully offline. One background thread only. */
class SignReader : Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** The longest line of text in the image, or null when there is nothing worth saying. */
    fun read(image: Bitmap): String? = try {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)))
        result.textBlocks
            .flatMap { it.lines }
            .map { it.text.trim() }
            .filter { it.length >= MIN_CHARS }
            .maxByOrNull { it.length }
    } catch (e: Exception) {
        Log.w(TAG, "Sign reading failed", e)
        null
    }

    override fun close() = recognizer.close()

    private companion object {
        const val MIN_CHARS = 3
    }
}

/** Traffic light colour from the pixels inside its box: bright red, yellow or green wins. */
object LightColor {
    fun classify(frame: Bitmap, left: Int, top: Int, right: Int, bottom: Int): TrafficLightColor? {
        val l = left.coerceIn(0, frame.width - 1)
        val t = top.coerceIn(0, frame.height - 1)
        val r = right.coerceIn(l + 1, frame.width)
        val b = bottom.coerceIn(t + 1, frame.height)
        var red = 0
        var yellow = 0
        var green = 0
        val hsv = FloatArray(3)
        var x = l
        while (x < r) {
            var y = t
            while (y < b) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]
                if (saturation >= MIN_SATURATION && value >= MIN_VALUE) {
                    when {
                        hue <= 15f || hue >= 345f -> red++
                        hue in 40f..70f -> yellow++
                        hue in 80f..170f -> green++
                    }
                }
                y += STEP
            }
            x += STEP
        }
        return TrafficLightColor.of(red, yellow, green)
    }

    private const val STEP = 2
    private const val MIN_SATURATION = 0.45f
    private const val MIN_VALUE = 0.5f
}
