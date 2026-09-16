package com.classroomscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.classroomscanner.R
import com.google.android.material.color.MaterialColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Ring of 36 segments that fill as the user turns; a dot marks the current heading. */
class CoverageRingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var covered = BooleanArray(36)
    private var heading = 0f
    private var percent = 0

    private val stroke = dp(8f)
    private val markerRadius = dp(6f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.ring_track)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.BUTT
        color = MaterialColors.getColor(this@CoverageRingView, com.google.android.material.R.attr.colorPrimary)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@CoverageRingView, com.google.android.material.R.attr.colorTertiary)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@CoverageRingView, com.google.android.material.R.attr.colorOnSurface)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
        isFakeBoldText = true
    }
    private val oval = RectF()

    init {
        contentDescription = context.getString(R.string.coverage_description, 0)
    }

    fun setState(covered: BooleanArray, heading: Float, percent: Int) {
        this.covered = covered
        this.heading = heading
        this.percent = percent
        contentDescription = context.getString(R.string.coverage_description, percent)
        invalidate()
    }

    fun reset() = setState(BooleanArray(36), 0f, 0)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = min(width, height) / 2f - maxOf(stroke / 2f, markerRadius)
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(oval, trackPaint)

        val sweep = 360f / covered.size
        covered.forEachIndexed { i, isCovered ->
            if (isCovered) canvas.drawArc(oval, -90f + i * sweep, sweep, false, fillPaint)
        }

        val rad = Math.toRadians((heading - 90f).toDouble())
        canvas.drawCircle(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat(), markerRadius, markerPaint)
        canvas.drawText("$percent%", cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
