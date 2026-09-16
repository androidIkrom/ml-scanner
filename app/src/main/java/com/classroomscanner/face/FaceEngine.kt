package com.classroomscanner.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.classroomscanner.core.FaceMatch
import com.classroomscanner.core.FaceMatcher
import com.classroomscanner.core.FaceNetPreprocess
import com.classroomscanner.core.KnownFace
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

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

/** ML Kit face detector (blocking calls; use a background thread). */
class FaceFinder(accurate: Boolean) : Closeable {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(
                if (accurate) FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE else FaceDetectorOptions.PERFORMANCE_MODE_FAST
            )
            .setMinFaceSize(0.1f)
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
            val crop = upright.cropFace(face.boundingBox) ?: return@mapNotNull null
            val match = FaceMatcher.bestMatch(embedder.embed(crop), known) ?: return@mapNotNull null
            RecognizedFace(face.boundingBox.exactCenterX(), face.boundingBox.exactCenterY(), match)
        }

    override fun close() {
        finder.close()
        embedder.close()
    }
}
