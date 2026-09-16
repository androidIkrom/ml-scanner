package com.classroomscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Ring of 36 segments that fill as the user turns; a dot marks the current heading. */
class CoverageRingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var covered = BooleanArray(36)
    private var heading = 0f
    private var percent = 0

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        color = Color.argb(90, 255, 255, 255)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        color = Color.rgb(76, 175, 80)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 36f
    }
    private val oval = RectF()

    fun setState(covered: BooleanArray, heading: Float, percent: Int) {
        this.covered = covered
        this.heading = heading
        this.percent = percent
        invalidate()
    }

    fun reset() = setState(BooleanArray(36), 0f, 0)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = min(width, height) / 2f - STROKE
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(oval, trackPaint)

        val sweep = 360f / covered.size
        covered.forEachIndexed { i, isCovered ->
            if (isCovered) canvas.drawArc(oval, -90f + i * sweep, sweep, false, fillPaint)
        }

        val rad = Math.toRadians((heading - 90f).toDouble())
        canvas.drawCircle(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat(), STROKE, markerPaint)
        canvas.drawText("$percent%", cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private companion object {
        const val STROKE = 10f
    }
}
