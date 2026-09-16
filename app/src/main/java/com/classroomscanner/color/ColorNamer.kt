package com.classroomscanner.color

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.palette.graphics.Palette
import com.classroomscanner.core.ColorMapper

object ColorNamer {
    private const val MIN_CROP_PX = 8
    private const val PALETTE_AREA = 48 * 48
    private const val DARK_LUMA = 0.15f
    private const val GRID = 16

    /** Dominant color name of the central half of [box]; [box] is in [frame] pixel coordinates. */
    fun name(frame: Bitmap, box: RectF, frameIsDark: Boolean): String? {
        if (frameIsDark) return null
        val w = box.width()
        val h = box.height()
        val x = (box.left + w / 4f).toInt().coerceIn(0, frame.width - 1)
        val y = (box.top + h / 4f).toInt().coerceIn(0, frame.height - 1)
        val cropW = (w / 2f).toInt().coerceAtMost(frame.width - x)
        val cropH = (h / 2f).toInt().coerceAtMost(frame.height - y)
        if (cropW < MIN_CROP_PX || cropH < MIN_CROP_PX) return null

        val crop = Bitmap.createBitmap(frame, x, y, cropW, cropH)
        val swatch = Palette.from(crop)
            .resizeBitmapArea(PALETTE_AREA)
            .maximumColorCount(8)
            .generate()
            .dominantSwatch ?: return null
        val hsv = FloatArray(3)
        Color.colorToHSV(swatch.rgb, hsv)
        return ColorMapper.nameFromHsv(hsv[0], hsv[1], hsv[2])
    }

    fun isDark(frame: Bitmap): Boolean {
        var sum = 0f
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val p = frame.getPixel(gx * (frame.width - 1) / (GRID - 1), gy * (frame.height - 1) / (GRID - 1))
                sum += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            }
        }
        return sum / (GRID * GRID) < DARK_LUMA
    }
}
