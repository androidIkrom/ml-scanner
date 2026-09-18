package com.classroomscanner.walk

import android.graphics.Bitmap
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.SemanticLabel
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import kotlin.math.asin

private const val TAG = "ClassroomScanner"

/** Metric depth of one frame, copied out so it can be used after the frame is gone. */
class DepthMap(private val width: Int, private val height: Int, private val millimetres: ShortArray) {

    /**
     * Metres to a box given in 0..1 fractions of the image: the median of samples in its lower half,
     * where the object meets the floor. Null when that part of the frame has no depth.
     */
    fun metres(left: Float, top: Float, right: Float, bottom: Float): Float? {
        val values = ArrayList<Float>(SAMPLES * SAMPLES)
        for (i in 0 until SAMPLES) {
            for (j in 0 until SAMPLES) {
                val fx = left + (right - left) * (i + 0.5f) / SAMPLES
                val fy = top + (bottom - top) * (0.5f + 0.5f * (j + 0.5f) / SAMPLES)
                val x = (fx * width).toInt().coerceIn(0, width - 1)
                val y = (fy * height).toInt().coerceIn(0, height - 1)
                val mm = millimetres[y * width + x].toInt() and 0x1FFF
                if (mm in MIN_MM..MAX_MM) values += mm / 1000f
            }
        }
        if (values.isEmpty()) return null
        values.sort()
        return values[values.size / 2]
    }

    private companion object {
        const val SAMPLES = 3
        const val MIN_MM = 100
        const val MAX_MM = 8_000
    }
}

/** Everything walk mode takes from one AR frame. */
class WalkFrame(
    val image: Bitmap,
    val depth: DepthMap?,
    /** Ground class under the walker, such as sidewalk or road; null without scene semantics. */
    val ground: String?,
    /** Negative when the phone looks down. */
    val pitchDeg: Float,
    val focalPx: Float,
)

/** Reads one AR frame on the GL thread; everything is guarded, because AR can drop frames. */
fun Frame.toWalkFrame(): WalkFrame? {
    val bitmap = cameraBitmap() ?: return null
    val intrinsics = camera.imageIntrinsics
    val focal = intrinsics.focalLength.getOrElse(0) { 0f }
    return WalkFrame(
        image = bitmap,
        depth = depthSnapshot(),
        ground = groundLabel(),
        pitchDeg = pitchDeg(),
        focalPx = if (focal > 0f) focal else bitmap.width.toFloat(),
    )
}

/** Copies the 16-bit depth image out of ARCore; null when depth is off or not ready. */
private fun Frame.depthSnapshot(): DepthMap? = try {
    acquireDepthImage16Bits().use { image ->
        val plane = image.planes[0]
        val buffer = plane.buffer.order(ByteOrder.nativeOrder())
        val rowStride = plane.rowStride
        val values = ShortArray(image.width * image.height)
        for (y in 0 until image.height) {
            val row = y * rowStride
            for (x in 0 until image.width) {
                val index = row + x * 2
                if (index + 1 < buffer.limit()) {
                    val low = buffer.get(index).toInt() and 0xFF
                    val high = buffer.get(index + 1).toInt() and 0xFF
                    values[y * image.width + x] = ((high shl 8) or low).toShort()
                }
            }
        }
        DepthMap(image.width, image.height, values)
    }
} catch (e: NotYetAvailableException) {
    null
} catch (e: Exception) {
    Log.w(TAG, "Depth snapshot failed", e)
    null
}

/** The scene semantics class in the middle of the bottom edge, where the walker's next step lands. */
private fun Frame.groundLabel(): String? = try {
    acquireSemanticImage().use { image ->
        val plane = image.planes[0]
        val buffer = plane.buffer
        val x = image.width / 2
        val y = (image.height * 0.9f).toInt().coerceIn(0, image.height - 1)
        val index = y * plane.rowStride + x * plane.pixelStride
        if (index >= buffer.limit()) {
            null
        } else {
            val label = SemanticLabel.values().getOrNull(buffer.get(index).toInt() and 0xFF)
            label?.takeIf { it in GROUND_LABELS }?.name?.lowercase()?.replace('_', ' ')
        }
    }
} catch (e: NotYetAvailableException) {
    null
} catch (e: Exception) {
    Log.w(TAG, "Semantics failed", e)
    null
}

private val GROUND_LABELS = setOf(
    SemanticLabel.ROAD,
    SemanticLabel.SIDEWALK,
    SemanticLabel.TERRAIN,
    SemanticLabel.WATER,
    SemanticLabel.STRUCTURE,
)

/** How far the phone is tilted down, from the AR camera pose; negative means looking down. */
private fun Frame.pitchDeg(): Float = try {
    // The camera looks along its negative Z axis; its Y part gives the tilt against the world's up.
    val z = camera.pose.zAxis
    val up = -z[1]
    Math.toDegrees(asin(up.coerceIn(-1f, 1f).toDouble())).toFloat()
} catch (e: Exception) {
    0f
}
