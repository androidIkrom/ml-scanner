package com.classroomscanner.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import java.io.Closeable
import java.util.Locale

/**
 * Names things the COCO detector has no class for. EfficientNet-Lite0 knows a thousand everyday
 * classes (doors, stairs, windows, food, tools), so "what is this" can answer even when the
 * detector saw nothing it could name. One background thread only.
 */
class SceneClassifier(context: Context) : Closeable {

    private val classifier = ImageClassifier.createFromOptions(
        context,
        ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(1)
            .setScoreThreshold(MIN_SCORE)
            .build()
    )

    /** The best guess for what is in the picture, in plain words, or null when it is unsure. */
    fun name(image: Bitmap): String? {
        val result = classifier.classify(BitmapImageBuilder(image).build())
        val best = result.classificationResult().classifications()
            .firstOrNull()?.categories()?.firstOrNull() ?: return null
        return best.categoryName()
            // ImageNet names hold several words for the same thing, and use underscores.
            .substringBefore(',')
            .replace('_', ' ')
            .lowercase(Locale.US)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    override fun close() = classifier.close()

    private companion object {
        const val MODEL = "efficientnet-lite0.tflite"
        const val MIN_SCORE = 0.35f
    }
}
