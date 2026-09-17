package com.classroomscanner.items

import android.content.Context
import android.graphics.Bitmap
import com.classroomscanner.core.ItemMatch
import com.classroomscanner.core.ItemMatcher
import com.classroomscanner.core.KnownItem
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable

private const val MIN_CROP_PX = 16

/** Crop of a box (pixels of this bitmap), clamped; null when too small. */
fun Bitmap.cropBox(left: Float, top: Float, right: Float, bottom: Float): Bitmap? {
    val l = left.toInt().coerceIn(0, width - 1)
    val t = top.toInt().coerceIn(0, height - 1)
    val r = right.toInt().coerceIn(l + 1, width)
    val b = bottom.toInt().coerceIn(t + 1, height)
    if (r - l < MIN_CROP_PX || b - t < MIN_CROP_PX) return null
    return Bitmap.createBitmap(this, l, t, r - l, b - t)
}

/** MediaPipe image embedder (mobilenet_v3_small, L2-normalized). One background thread only. */
class ItemEmbedder(context: Context) : Closeable {
    private val embedder = ImageEmbedder.createFromOptions(
        context,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setL2Normalize(true)
            .setQuantize(false)
            .build()
    )

    fun embed(crop: Bitmap): FloatArray =
        embedder.embed(BitmapImageBuilder(crop).build())
            .embeddingResult().embeddings()[0].floatEmbedding()

    override fun close() = embedder.close()

    private companion object {
        const val MODEL = "mobilenet_v3_small.tflite"
    }
}

/** Matches upright crops of detections against saved items. One background thread only. */
class ItemRecognizer(context: Context, private val known: List<KnownItem>) : Closeable {
    private val embedder = ItemEmbedder(context)

    /** Detector labels that have at least one saved item. */
    val labels: Set<String> = known.map { it.label }.toSet()

    /** A null [label] compares the crop with items of every label. */
    fun match(crop: Bitmap, label: String?): ItemMatch? {
        if (label != null && label !in labels) return null
        return ItemMatcher.bestMatch(embedder.embed(crop), label, known)
    }

    override fun close() = embedder.close()
}
