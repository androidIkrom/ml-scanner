/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.classroomscanner

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: ObjectDetectorResult? = null
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var scaleFactor: Float = 1f
    private var bounds = Rect()
    private var outputWidth = 0
    private var outputHeight = 0
    private var outputRotate = 0
    private var runningMode: RunningMode = RunningMode.IMAGE
    private var labels: List<String?>? = null
    private var outlines: List<FloatArray?>? = null
    private val outlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val shapePath = Path()

    /** True for the front camera: its preview is mirrored, so boxes are flipped horizontally too. */
    var mirrored: Boolean = false

    init {
        initPaints()
    }

    fun clear() {
        results = null
        labels = null
        outlines = null
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        invalidate()
        initPaints()
    }

    fun setRunningMode(runningMode: RunningMode) {
        this.runningMode = runningMode
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.color = ContextCompat.getColor(context!!, R.color.mp_primary)
        outlinePaint.color = boxPaint.color
        fillPaint.color = boxPaint.color
        fillPaint.alpha = FILL_ALPHA
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val toView = imageToViewMatrix()
        results?.detections()?.map {
            val boxRect = RectF(
                it.boundingBox().left,
                it.boundingBox().top,
                it.boundingBox().right,
                it.boundingBox().bottom
            )
            toView.mapRect(boxRect)
            if (mirrored) {
                boxRect.set(rotatedWidth() - boxRect.right, boxRect.top, rotatedWidth() - boxRect.left, boxRect.bottom)
            }
            boxRect
        }?.forEachIndexed { index, floats ->
            // With custom labels, a null entry means the detection was filtered out: do not draw it.
            val customLabel = labels?.let { it.getOrNull(index) ?: return@forEachIndexed }

            val top = floats.top * scaleFactor
            val bottom = floats.bottom * scaleFactor
            val left = floats.left * scaleFactor
            val right = floats.right * scaleFactor

            // Draw bounding box around detected objects
            val outline = outlines?.getOrNull(index)
            if (outline != null && outline.isNotEmpty()) {
                // Draw the object's shape instead of its box.
                val points = outline.copyOf()
                toView.mapPoints(points)
                shapePath.reset()
                for (i in points.indices step 2) {
                    val x = (if (mirrored) rotatedWidth() - points[i] else points[i]) * scaleFactor
                    val y = points[i + 1] * scaleFactor
                    if (i == 0) shapePath.moveTo(x, y) else shapePath.lineTo(x, y)
                }
                shapePath.close()
                canvas.drawPath(shapePath, fillPaint)
                canvas.drawPath(shapePath, outlinePaint)
            } else {
                canvas.drawRect(RectF(left, top, right, bottom), boxPaint)
            }

            // Create text to display alongside detected objects
            val category = results?.detections()!![index].categories()[0]
            val drawableText = customLabel
                ?: (category.categoryName() + " " + String.format("%.2f", category.score()))

            // Draw rect behind display text
            textBackgroundPaint.getTextBounds(
                drawableText,
                0,
                drawableText.length,
                bounds
            )
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            canvas.drawRect(
                left,
                top,
                left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                textBackgroundPaint
            )

            // Draw text for detected object
            canvas.drawText(
                drawableText,
                left,
                top + bounds.height(),
                textPaint
            )
        }
    }

    fun setResults(
        detectionResults: ObjectDetectorResult,
        outputHeight: Int,
        outputWidth: Int,
        imageRotation: Int,
        labels: List<String?>? = null,
        outlines: List<FloatArray?>? = null
    ) {
        this.labels = labels
        this.outlines = outlines
        results = detectionResults
        this.outputWidth = outputWidth
        this.outputHeight = outputHeight
        this.outputRotate = imageRotation

        // Calculates the new width and height of an image after it has been rotated.
        // If `imageRotation` is 0 or 180, the new width and height are the same
        // as the original width and height.
        // If `imageRotation` is 90 or 270, the new width and height are swapped.
        val rotatedWidthHeight = when (imageRotation) {
            0, 180 -> Pair(outputWidth, outputHeight)
            90, 270 -> Pair(outputHeight, outputWidth)
            else -> return
        }

        // Images, videos are displayed in FIT_START mode.
        // Camera live streams is displayed in FILL_START mode. So we need to scale
        // up the bounding box to match with the size that the images/videos/live streams being
        // displayed.
        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(
                    width * 1f / rotatedWidthHeight.first,
                    height * 1f / rotatedWidthHeight.second
                )
            }

            RunningMode.LIVE_STREAM -> {
                max(
                    width * 1f / rotatedWidthHeight.first,
                    height * 1f / rotatedWidthHeight.second
                )
            }
        }

        invalidate()
    }

    /** Maps unrotated image pixels to the upright image (before scaling to the view). */
    private fun imageToViewMatrix(): Matrix = Matrix().apply {
        postTranslate(-outputWidth / 2f, -outputHeight / 2f)
        postRotate(outputRotate.toFloat())
        // After a 90 or 270 degree turn the image's width and height swap.
        if (outputRotate == 90 || outputRotate == 270) {
            postTranslate(outputHeight / 2f, outputWidth / 2f)
        } else {
            postTranslate(outputWidth / 2f, outputHeight / 2f)
        }
    }

    private fun rotatedWidth(): Int = if (outputRotate == 90 || outputRotate == 270) outputHeight else outputWidth

    companion object {
        private const val FILL_ALPHA = 70
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}
