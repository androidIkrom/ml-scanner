package com.classroomscanner.walk

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.io.Closeable

private const val TAG = "ClassroomScanner"

/** Why walk mode cannot use ARCore, or null when it can. */
enum class ArProblem { NOT_INSTALLED, NOT_SUPPORTED, FAILED }

/**
 * ARCore for walk mode: it owns the camera and gives the camera image, metric depth and, where the
 * phone supports it, ground classes. Everything is guarded, because AR is missing on many phones.
 */
class ArCamera(private val activity: Activity) : Closeable {

    var session: Session? = null
        private set

    var depthSupported = false
        private set

    var semanticsSupported = false
        private set

    /** Creates the session; returns null on success or the reason it could not start. */
    fun open(): ArProblem? {
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return ArProblem.NOT_INSTALLED
                ArCoreApk.InstallStatus.INSTALLED -> Unit
                null -> return ArProblem.FAILED
            }
            val created = Session(activity)
            val config = created.config
            depthSupported = created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (depthSupported) config.depthMode = Config.DepthMode.AUTOMATIC
            semanticsSupported = created.isSemanticModeSupported(Config.SemanticMode.ENABLED)
            if (semanticsSupported) config.semanticMode = Config.SemanticMode.ENABLED
            config.focusMode = Config.FocusMode.AUTO
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
            created.configure(config)
            session = created
            return null
        } catch (e: UnavailableException) {
            Log.w(TAG, "ARCore unavailable", e)
            return ArProblem.NOT_SUPPORTED
        } catch (e: Exception) {
            Log.w(TAG, "ARCore failed to start", e)
            return ArProblem.FAILED
        }
    }

    /** Must be called from the GL thread before the first [update]. */
    fun useTexture(textureId: Int) {
        session?.setCameraTextureName(textureId)
    }

    fun resume(): Boolean = try {
        session?.resume()
        true
    } catch (e: CameraNotAvailableException) {
        Log.w(TAG, "Camera not available for AR", e)
        false
    }

    fun pause() {
        try {
            session?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "Pausing AR failed", e)
        }
    }

    /** One AR frame, or null when the session is not running. GL thread only. */
    fun update(): Frame? = try {
        session?.update()
    } catch (e: Exception) {
        Log.w(TAG, "AR update failed", e)
        null
    }

    override fun close() {
        session?.close()
        session = null
    }

    companion object {
        /** Whether this phone can run AR at all, without creating a session. */
        fun supported(activity: Activity): Boolean =
            ArCoreApk.getInstance().checkAvailability(activity).isSupported
    }
}

/**
 * The camera image as a small RGB bitmap. The YUV planes are read straight into pixels, skipping
 * rows and columns, because the detector works on a small image anyway: going through JPEG cost
 * more than the detector itself. GL thread only.
 */
fun Frame.cameraBitmap(maxWidth: Int = 320): Bitmap? = try {
    acquireCameraImage().use { it.toSmallBitmap(maxWidth) }
} catch (e: NotYetAvailableException) {
    null
} catch (e: Exception) {
    Log.w(TAG, "Camera image failed", e)
    null
}

private fun Image.toSmallBitmap(maxWidth: Int): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null
    var step = 1
    while (width / (step + 1) >= maxWidth) step++
    val outWidth = width / step
    val outHeight = height / step
    if (outWidth <= 0 || outHeight <= 0) return null

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val pixels = IntArray(outWidth * outHeight)

    for (row in 0 until outHeight) {
        val y = row * step
        val yRow = y * yPlane.rowStride
        val uvRow = (y / 2) * uPlane.rowStride
        for (col in 0 until outWidth) {
            val x = col * step
            val luma = yBuffer.get(yRow + x * yPlane.pixelStride).toInt() and 0xFF
            val uvIndex = uvRow + (x / 2) * uPlane.pixelStride
            val u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
            val v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128
            // Integer YUV to RGB, the usual BT.601 coefficients scaled by 1024.
            val r = (luma + 1436 * v / 1024).coerceIn(0, 255)
            val g = (luma - 352 * u / 1024 - 731 * v / 1024).coerceIn(0, 255)
            val b = (luma + 1814 * u / 1024).coerceIn(0, 255)
            pixels[row * outWidth + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
}
