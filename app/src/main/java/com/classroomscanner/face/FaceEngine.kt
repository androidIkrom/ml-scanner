package com.classroomscanner.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.classroomscanner.core.FaceMatch
import com.classroomscanner.core.FaceMatcher
import com.classroomscanner.core.FaceNetPreprocess
import com.classroomscanner.core.FaceQuality
import com.classroomscanner.core.KnownFace
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.atan2

/** Rotates a camera buffer so faces are upright for ML Kit and FaceNet. */
fun Bitmap.upright(rotationDegrees: Int): Bitmap {
    if (rotationDegrees % 360 == 0) return this
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** Crop of [box] grown by [margin] on each side, clamped to the bitmap; null when too small. */
fun Bitmap.cropFace(box: Rect, margin: Float = 0.1f): Bitmap? {
    val dx = (box.width() * margin).toInt()
    val dy = (box.height() * margin).toInt()
    val left = (box.left - dx).coerceIn(0, width - 1)
    val top = (box.top - dy).coerceIn(0, height - 1)
    val right = (box.right + dx).coerceIn(left + 1, width)
    val bottom = (box.bottom + dy).coerceIn(top + 1, height)
    if (right - left < MIN_FACE_PX || bottom - top < MIN_FACE_PX) return null
    return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
}

private const val MIN_FACE_PX = 24

/**
 * The face turned so its eyes are level, cropped with a small margin. FaceNet was trained on level
 * faces, and a tilted head alone was enough to make two people look alike. Falls back to the plain
 * crop when the eyes were not found.
 */
fun Bitmap.alignedFace(face: Face): Bitmap? {
    val box = face.boundingBox
    val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
    val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
    if (left == null || right == null) return cropFace(box)
    val tilt = Math.toDegrees(atan2((right.y - left.y).toDouble(), (right.x - left.x).toDouble())).toFloat()
    if (abs(tilt) < MIN_TILT_DEG) return cropFace(box)
    val wide = cropFace(box, margin = WIDE_MARGIN) ?: return null
    val level = Bitmap.createBitmap(
        wide, 0, 0, wide.width, wide.height, Matrix().apply { postRotate(-tilt) }, true,
    )
    // The face sits in the middle of the turned picture; keep it with the usual margin.
    val width = (box.width() * (1 + 2 * FACE_MARGIN)).toInt().coerceAtMost(level.width)
    val height = (box.height() * (1 + 2 * FACE_MARGIN)).toInt().coerceAtMost(level.height)
    if (width < MIN_FACE_PX || height < MIN_FACE_PX) return null
    return Bitmap.createBitmap(level, (level.width - width) / 2, (level.height - height) / 2, width, height)
}

private const val MIN_TILT_DEG = 3f
private const val WIDE_MARGIN = 0.35f
private const val FACE_MARGIN = 0.1f

/** ML Kit face detector (blocking calls; use a background thread). */
class FaceFinder(accurate: Boolean) : Closeable {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(
                if (accurate) FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE else FaceDetectorOptions.PERFORMANCE_MODE_FAST
            )
            .setMinFaceSize(0.1f)
            // Eye positions are needed to level the face before it is compared.
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    /** Faces in [upright] pixel coordinates, with head angles (headEulerAngleY / X). */
    fun find(upright: Bitmap): List<Face> = Tasks.await(detector.process(InputImage.fromBitmap(upright, 0)))

    override fun close() = detector.close()
}

/** FaceNet-512 embeddings with the TFLite interpreter (not thread-safe; one thread only). */
class FaceEmbedder(context: Context) : Closeable {
    private val interpreter: Interpreter
    private val outputSize: Int

    init {
        val fd = context.assets.openFd(MODEL)
        val model = FileInputStream(fd.fileDescriptor).channel.use {
            it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
        outputSize = interpreter.getOutputTensor(0).shape()[1]
    }

    /**
     * The embedding of the face and of its mirror image, averaged. One photo's lighting or angle
     * then moves the result less, so the same person matches more steadily.
     */
    fun embedSteady(face: Bitmap): FloatArray {
        val flipped = Bitmap.createBitmap(
            face, 0, 0, face.width, face.height, Matrix().apply { preScale(-1f, 1f) }, true,
        )
        val a = embed(face)
        val b = embed(flipped)
        return FloatArray(a.size) { (a[it] + b[it]) / 2f }
    }

    fun embed(face: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(face, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val rgb = FloatArray(SIZE * SIZE * 3)
        pixels.forEachIndexed { i, p ->
            rgb[i * 3] = ((p shr 16) and 0xFF).toFloat()
            rgb[i * 3 + 1] = ((p shr 8) and 0xFF).toFloat()
            rgb[i * 3 + 2] = (p and 0xFF).toFloat()
        }
        val input = ByteBuffer.allocateDirect(rgb.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(FaceNetPreprocess.standardize(rgb))
        val output = Array(1) { FloatArray(outputSize) }
        interpreter.run(input, output)
        return output[0]
    }

    override fun close() = interpreter.close()

    private companion object {
        const val MODEL = "facenet_512.tflite"
        const val SIZE = 160
        const val THREADS = 4
    }
}

/** A face found in a frame, in upright pixel coordinates, and who it is. */
class RecognizedFace(val centerX: Float, val centerY: Float, val match: FaceMatch)

/** Finds faces in a frame and matches them against saved people. One background thread only. */
class FaceRecognizer(context: Context, private val known: List<KnownFace>) : Closeable {
    private val finder = FaceFinder(accurate = false)
    private val embedder = FaceEmbedder(context)

    fun recognize(upright: Bitmap): List<RecognizedFace> =
        finder.find(upright).mapNotNull { face ->
            val box = face.boundingBox
            // Small or turned-away faces give embeddings that look like anybody; skip them.
            val size = minOf(box.width(), box.height())
            if (!FaceQuality.usable(size, face.headEulerAngleY, face.headEulerAngleX)) return@mapNotNull null
            val crop = upright.alignedFace(face) ?: return@mapNotNull null
            val match = FaceMatcher.bestMatch(embedder.embedSteady(crop), known) ?: return@mapNotNull null
            RecognizedFace(box.exactCenterX(), box.exactCenterY(), match)
        }

    override fun close() {
        finder.close()
        embedder.close()
    }
}
