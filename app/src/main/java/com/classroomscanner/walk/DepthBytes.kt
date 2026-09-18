package com.classroomscanner.walk

import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

private const val TAG = "ClassroomScanner"

/**
 * The depth frame's millimetres, copied in bulk on the GL thread into a buffer that is reused, so
 * walking for minutes does not fill memory with throw-away arrays.
 */
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
                val shorts = buffer.asShortBuffer()
                if (plane.rowStride == width * 2) {
                    // The usual case: one bulk read of the whole image.
                    shorts.get(values, 0, minOf(size, shorts.remaining()))
                } else {
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
