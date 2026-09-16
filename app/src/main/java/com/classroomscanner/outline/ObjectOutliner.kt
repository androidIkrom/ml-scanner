package com.classroomscanner.outline

import android.content.Context
import android.graphics.Bitmap
import com.classroomscanner.core.MaskOutline
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
     * Outline segments for each box (`[left, top, right, bottom]` in [frame] pixels), in frame pixels;
     * null for a box whose shape could not be found.
     */
    fun outline(frame: Bitmap, boxes: List<FloatArray>): List<FloatArray?> {
        if (boxes.isEmpty()) return emptyList()
        segmenter.setImage(BitmapImageBuilder(frame).build())
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        return boxes.map { box ->
            val cx = (box[0] + box[2]) / 2f / w
            val cy = (box[1] + box[3]) / 2f / h
            val reach = (box[3] - box[1]) / h * STROKE_FRACTION
            val stroke = Stroke.builder()
                .setBrushMode(Stroke.BrushMode.POSITIVE)
                .setPoints(
                    listOf(
                        NormalizedKeypoint.create(cx, (cy - reach).coerceIn(0f, 1f)),
                        NormalizedKeypoint.create(cx, cy),
                        NormalizedKeypoint.create(cx, (cy + reach).coerceIn(0f, 1f)),
                    )
                )
                .setCompleted(true)
                .build()
            val mask = segmenter.segment(listOf(stroke))
            val values = FloatArray(mask.width * mask.height)
            ByteBufferExtractor.extract(mask).asFloatBuffer().get(values)
            // The mask may be smaller than the frame; work in mask pixels, then scale back.
            val sx = mask.width / w
            val sy = mask.height / h
            val segments = MaskOutline.segments(
                values, mask.width, mask.height,
                box[0] * sx, box[1] * sy, box[2] * sx, box[3] * sy,
            )
            if (segments.isEmpty()) {
                null
            } else {
                for (i in segments.indices) segments[i] /= if (i % 2 == 0) sx else sy
                segments
            }
        }
    }

    override fun close() = segmenter.close()

    private companion object {
        const val MODEL = "interactive_segmentation.task"

        /** Half-length of the vertical stroke, as a share of the box height. */
        const val STROKE_FRACTION = 0.15f
    }
}
