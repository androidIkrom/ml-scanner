package com.classroomscanner.outline

import android.content.Context
import android.graphics.Bitmap
import com.classroomscanner.core.BoxGeometry
import com.classroomscanner.core.MaskOutline
import com.classroomscanner.face.upright
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import java.io.Closeable

/**
 * Finds the shape of each detected object with MediaPipe's interactive segmenter (MagicTouch v2):
 * the image is set once, then a short positive stroke through each box's center selects that object.
 * Not thread-safe; use from one background thread.
 */
class ObjectOutliner(context: Context) : Closeable {

    private val segmenter = InteractiveSegmenter.createFromOptions(
        context,
        InteractiveSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .build()
    )

    /**
     * A smooth closed outline for each box (`[left, top, right, bottom]` in [frame] pixels), as
     * `x0, y0, x1, y1, ...` in frame pixels; null for a box whose shape could not be found.
     */
    fun outline(frame: Bitmap, boxes: List<FloatArray>): List<FloatArray?> {
        if (boxes.isEmpty()) return emptyList()
        segmenter.setImage(BitmapImageBuilder(frame).build())
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        return boxes.map { box ->
            val mask = segmenter.segment(strokesFor(box, w, h))
            val values = FloatArray(mask.width * mask.height)
            ByteBufferExtractor.extract(mask).asFloatBuffer().get(values)
            // The mask may be smaller than the frame; work in mask pixels, then scale back.
            val sx = mask.width / w
            val sy = mask.height / h
            val loop = MaskOutline.smooth(
                MaskOutline.largestLoop(
                    MaskOutline.segments(
                        values, mask.width, mask.height,
                        box[0] * sx, box[1] * sy, box[2] * sx, box[3] * sy,
                    )
                )
            )
            if (loop.size < MIN_POINTS * 2) {
                null
            } else {
                for (i in loop.indices) loop[i] /= if (i % 2 == 0) sx else sy
                loop
            }
        }
    }

    /**
     * Like [outline], but [frame] is an unrotated camera buffer and [boxes] are in its pixels.
     * The model works best on upright images, so the frame is turned first and the shapes are
     * turned back.
     */
    fun outlineRaw(frame: Bitmap, rotationDegrees: Int, boxes: List<FloatArray>): List<FloatArray?> {
        if (boxes.isEmpty()) return emptyList()
        val upright = frame.upright(rotationDegrees)
        val uprightBoxes = boxes.map {
            BoxGeometry.toUpright(it[0], it[1], it[2], it[3], frame.width, frame.height, rotationDegrees)
        }
        return outline(upright, uprightBoxes).map { points ->
            points?.let { BoxGeometry.uprightPointsToRaw(it, frame.width, frame.height, rotationDegrees) }
        }
    }

    /**
     * A positive cross through the middle of the box picks the object; a negative ring just outside
     * the box keeps the table, wall or floor behind it out of the shape.
     */
    private fun strokesFor(box: FloatArray, w: Float, h: Float): List<Stroke> {
        fun p(x: Float, y: Float) = NormalizedKeypoint.create((x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f))
        val cx = (box[0] + box[2]) / 2f
        val cy = (box[1] + box[3]) / 2f
        val rx = (box[2] - box[0]) * CROSS_FRACTION
        val ry = (box[3] - box[1]) * CROSS_FRACTION
        val padX = (box[2] - box[0]) * RING_PADDING
        val padY = (box[3] - box[1]) * RING_PADDING
        val l = box[0] - padX
        val t = box[1] - padY
        val r = box[2] + padX
        val b = box[3] + padY
        return listOf(
            stroke(Stroke.BrushMode.POSITIVE, listOf(p(cx, cy - ry), p(cx, cy), p(cx, cy + ry))),
            stroke(Stroke.BrushMode.POSITIVE, listOf(p(cx - rx, cy), p(cx, cy), p(cx + rx, cy))),
            stroke(Stroke.BrushMode.NEGATIVE, listOf(p(l, t), p(r, t), p(r, b), p(l, b), p(l, t))),
        )
    }

    private fun stroke(mode: Stroke.BrushMode, points: List<NormalizedKeypoint>): Stroke =
        Stroke.builder().setBrushMode(mode).setPoints(points).setCompleted(true).build()

    override fun close() = segmenter.close()

    private companion object {
        const val MODEL = "interactive_segmentation.task"

        /** Half-length of each arm of the positive cross, as a share of the box size. */
        const val CROSS_FRACTION = 0.15f

        /** How far outside the box the negative ring runs, as a share of the box size. */
        const val RING_PADDING = 0.04f

        /** Outlines with fewer points are noise. */
        const val MIN_POINTS = 8
    }
}
