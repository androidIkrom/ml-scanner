package com.classroomscanner.walk

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.io.ByteArrayOutputStream
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

/** The camera image as a bitmap, or null when it is not ready. GL thread only. */
fun Frame.cameraBitmap(): Bitmap? = try {
    acquireCameraImage().use { it.toBitmap() }
} catch (e: NotYetAvailableException) {
    null
} catch (e: Exception) {
    Log.w(TAG, "Camera image failed", e)
    null
}

private fun Image.toBitmap(): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null
    val nv21 = yuvToNv21(this)
    val out = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, width, height, null)
        .compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
    val bytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private const val JPEG_QUALITY = 85

private fun yuvToNv21(image: Image): ByteArray {
    val y = image.planes[0].buffer
    val u = image.planes[1].buffer
    val v = image.planes[2].buffer
    val ySize = y.remaining()
    val uSize = u.remaining()
    val vSize = v.remaining()
    val out = ByteArray(ySize + uSize + vSize)
    y.get(out, 0, ySize)
    // NV21 wants V and U interleaved; the planes are already close to that layout.
    v.get(out, ySize, vSize)
    u.get(out, ySize + vSize, uSize)
    return out
}
