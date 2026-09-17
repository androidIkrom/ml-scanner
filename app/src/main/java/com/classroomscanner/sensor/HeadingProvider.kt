package com.classroomscanner.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.classroomscanner.core.AngleMath
import kotlin.math.abs

/** Heading relative to where the phone pointed when [start] was called. Phone must be held upright. */
class HeadingProvider(context: Context, private val listener: Listener) : SensorEventListener {

    interface Listener {
        fun onHeading(relHeading: Float, speedDegPerSec: Float)
        fun onAccuracyLow(low: Boolean)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean get() = sensor != null

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var filtered: Float? = null
    private var startHeading: Float? = null
    private var lastRel: Float? = null
    private var lastTimestampNs = 0L
    private var speed = 0f

    fun start() {
        filtered = null
        startHeading = null
        lastRel = null
        speed = 0f
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Phone held upright (portrait, camera facing forward): remap so azimuth stays stable.
        SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val azimuth = AngleMath.normalize(Math.toDegrees(orientation[0].toDouble()).toFloat())

        val smoothed = filtered?.let { AngleMath.normalize(it + ALPHA * AngleMath.diff(azimuth, it)) } ?: azimuth
        filtered = smoothed
        val start = startHeading ?: smoothed.also { startHeading = it }
        val rel = AngleMath.normalize(smoothed - start)

        lastRel?.let { prev ->
            val dt = (event.timestamp - lastTimestampNs) / 1_000_000_000f
            if (dt > 0f) speed = SPEED_ALPHA * (abs(AngleMath.diff(rel, prev)) / dt) + (1 - SPEED_ALPHA) * speed
        }
        lastRel = rel
        lastTimestampNs = event.timestamp

        listener.onHeading(rel, speed)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        listener.onAccuracyLow(accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW)
    }

    companion object {
        /** True when the phone has the rotation sensor Full Scan needs. */
        fun isAvailable(context: Context): Boolean =
            HeadingProvider(context, object : Listener {
                override fun onHeading(relHeading: Float, speedDegPerSec: Float) = Unit
                override fun onAccuracyLow(low: Boolean) = Unit
            }).isAvailable

        private const val ALPHA = 0.3f
        private const val SPEED_ALPHA = 0.2f
    }
}
