package com.classroomscanner.walk

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "ClassroomScanner"

/**
 * The raw bytes of one camera frame, copied out of ARCore on the GL thread and turned into pixels
 * on a worker thread. The buffers are kept and refilled, so walking does not fill memory with
 * throw-away frames, and the GL thread only does bulk copies.
 */
class FrameBytes {
    private var y = ByteArray(0)
    private var u = ByteArray(0)
    private var v = ByteArray(0)

    var width = 0
        private set
    var height = 0
        private set
    private var yRowStride = 0
    private var uvRowStride = 0
    private var uvPixelStride = 0

    /** Fills the buffers from the AR frame. GL thread only; returns false when no frame is ready. */
    fun copyFrom(frame: Frame): Boolean = try {
        frame.acquireCameraImage().use { image ->
            if (image.format != ImageFormat.YUV_420_888) {
                false
            } else {
                width = image.width
                height = image.height
                yRowStride = image.planes[0].rowStride
                uvRowStride = image.planes[1].rowStride
                uvPixelStride = image.planes[1].pixelStride
                y = copyPlane(image.planes[0].buffer, y)
                u = copyPlane(image.planes[1].buffer, u)
                v = copyPlane(image.planes[2].buffer, v)
                true
            }
        }
    } catch (e: NotYetAvailableException) {
        false
    } catch (e: Exception) {
        Log.w(TAG, "Copying the camera frame failed", e)
        false
    }

    /**
     * An RGB bitmap at most [maxWidth] wide, made by skipping rows and columns. Worker thread; the
     * caller owns the bitmap.
     */
    fun toBitmap(maxWidth: Int): Bitmap? {
        if (width == 0 || height == 0) return null
        var step = 1
        while (width / (step + 1) >= maxWidth) step++
        val outWidth = width / step
        val outHeight = height / step
        if (outWidth <= 0 || outHeight <= 0) return null
        val pixels = IntArray(outWidth * outHeight)
        for (row in 0 until outHeight) {
            val sourceY = row * step
            val yRow = sourceY * yRowStride
            val uvRow = (sourceY / 2) * uvRowStride
            for (col in 0 until outWidth) {
                val x = col * step
                val luma = y.getOrElse(yRow + x) { 0 }.toInt() and 0xFF
                val uvIndex = uvRow + (x / 2) * uvPixelStride
                val cb = (u.getOrElse(uvIndex) { 128.toByte() }.toInt() and 0xFF) - 128
                val cr = (v.getOrElse(uvIndex) { 128.toByte() }.toInt() and 0xFF) - 128
                // Integer BT.601, scaled by 1024.
                val r = (luma + 1436 * cr / 1024).coerceIn(0, 255)
                val g = (luma - 352 * cb / 1024 - 731 * cr / 1024).coerceIn(0, 255)
                val b = (luma + 1814 * cb / 1024).coerceIn(0, 255)
                pixels[row * outWidth + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }

    private fun copyPlane(buffer: ByteBuffer, into: ByteArray): ByteArray {
        val size = buffer.remaining()
        val out = if (into.size == size) into else ByteArray(size)
        buffer.get(out)
        return out
    }
}

/** The depth frame's millimetres, copied in bulk on the GL thread into a buffer that is reused. */
class DepthBytes {
    private var values = ShortArray(0)

    var width = 0
        private set
    var height = 0
        private set
    var filled = false
        private set

    /** GL thread only. */
    fun copyFrom(frame: Frame): Boolean {
        filled = false
        try {
            frame.acquireDepthImage16Bits().use { image ->
                val plane = image.planes[0]
                val buffer = plane.buffer.order(ByteOrder.nativeOrder())
                width = image.width
                height = image.height
                val size = width * height
                if (values.size != size) values = ShortArray(size)
                if (plane.rowStride == width * 2) {
                    // The usual case: one bulk read of the whole image.
                    buffer.asShortBuffer().get(values, 0, minOf(size, buffer.remaining() / 2))
                } else {
                    val shorts = buffer.asShortBuffer()
                    val strideShorts = plane.rowStride / 2
                    for (row in 0 until height) {
                        val from = row * strideShorts
                        if (from + width <= shorts.limit()) {
                            shorts.position(from)
                            shorts.get(values, row * width, width)
                        }
                    }
                }
                filled = true
            }
        } catch (e: NotYetAvailableException) {
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Copying depth failed", e)
            return false
        }
        return filled
    }

    /** A depth map over the copied values; valid until the next copy. */
    fun map(): DepthMap? = if (filled) DepthMap(width, height, values) else null
}
