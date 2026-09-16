package com.classroomscanner.color

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.classroomscanner.core.ColorVote
import com.classroomscanner.core.WhiteBalance

object ColorNamer {
    private const val MIN_CROP_PX = 8
    private const val SAMPLE_PX = 24
    private const val DARK_LUMA = 0.15f
    private const val GRID = 16

    /** Brightness and white-balance correction measured once per frame. */
    class FrameStats(val isDark: Boolean, val gains: WhiteBalance.Gains)

    /** Color name of the central half of [box] ([frame] pixel coordinates), or null when unknown. */
    fun name(frame: Bitmap, box: RectF, stats: FrameStats): String? {
        if (stats.isDark) return null
        val w = box.width()
        val h = box.height()
        val x = (box.left + w / 4f).toInt().coerceIn(0, frame.width - 1)
        val y = (box.top + h / 4f).toInt().coerceIn(0, frame.height - 1)
        val cropW = (w / 2f).toInt().coerceAtMost(frame.width - x)
        val cropH = (h / 2f).toInt().coerceAtMost(frame.height - y)
        if (cropW < MIN_CROP_PX || cropH < MIN_CROP_PX) return null

        val crop = Bitmap.createBitmap(frame, x, y, cropW, cropH)
        val sample = Bitmap.createScaledBitmap(crop, SAMPLE_PX, SAMPLE_PX, true)
        val pixels = IntArray(SAMPLE_PX * SAMPLE_PX)
        sample.getPixels(pixels, 0, SAMPLE_PX, 0, 0, SAMPLE_PX, SAMPLE_PX)
        if (sample !== crop) sample.recycle()
        if (crop !== frame) crop.recycle()
        return ColorVote.nameOfPixels(pixels, stats.gains)
    }

    /** Samples a grid over [frame] for its mean brightness and gray-world white balance. */
    fun frameStats(frame: Bitmap): FrameStats {
        var r = 0f
        var g = 0f
        var b = 0f
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val p = frame.getPixel(gx * (frame.width - 1) / (GRID - 1), gy * (frame.height - 1) / (GRID - 1))
                r += Color.red(p)
                g += Color.green(p)
                b += Color.blue(p)
            }
        }
        val n = (GRID * GRID).toFloat()
        r /= n
        g /= n
        b /= n
        val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        return FrameStats(luma < DARK_LUMA, WhiteBalance.gains(r, g, b))
    }
}
