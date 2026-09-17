package com.classroomscanner.fragments

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.classroomscanner.ObjectDetectorHelper
import com.classroomscanner.R
import com.classroomscanner.core.Candidate
import com.classroomscanner.core.CenterPick
import com.classroomscanner.core.ItemEnrollmentGuide
import com.classroomscanner.core.ItemKind
import com.classroomscanner.core.ItemStep
import com.classroomscanner.databinding.FragmentEnrollBinding
import com.classroomscanner.face.upright
import com.classroomscanner.items.ItemEmbedder
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.items.cropBox
import com.classroomscanner.speech.SpeechAnnouncer
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Step 2 of adding a car or object: finds the thing in the middle of the view, collects image
 * embeddings from three spots, then saves it. Model work runs on [executor] only.
 */
class ItemEnrollFragment : Fragment() {

    private val args: ItemEnrollFragmentArgs by navArgs()
    private var _binding: FragmentEnrollBinding? = null
    private val binding get() = _binding!!

    private lateinit var executor: ExecutorService
    private lateinit var speech: SpeechAnnouncer

    // Executor thread only.
    private var detector: ObjectDetectorHelper? = null
    private var embedder: ItemEmbedder? = null
    private val guide = ItemEnrollmentGuide()
    private val vectors = mutableListOf<FloatArray>()
    private var lockedLabel: String? = null
    private var photo: Bitmap? = null
    private var lastSampleAt = 0L
    private var lastWarningAt = 0L
    private var finished = false

    private val kind get() = ItemKind.valueOf(args.kind)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEnrollBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext().applicationContext
        speech = SpeechAnnouncer(context)
        executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                detector = ObjectDetectorHelper(
                    threshold = MIN_SCORE,
                    currentModel = ObjectDetectorHelper.MODEL_EFFICIENTDETV2,
                    runningMode = RunningMode.IMAGE,
                    context = context,
                )
                embedder = ItemEmbedder(context)
            } catch (e: Exception) {
                Log.e(TAG, "Item models failed to load", e)
                finished = true
                onMain { showStatus(getString(R.string.item_enroll_failed), speakIt = true) }
            }
        }
        showProgress(0)
        binding.instruction.text = ItemStep.STILL.instruction
        view.postDelayed({ if (_binding != null) speech.speakNow(ItemStep.STILL.instruction) }, FIRST_SPEECH_DELAY_MS)
        binding.viewFinder.post { setUpCamera() }
    }

    override fun onDestroyView() {
        speech.shutdownWhenIdle()
        _binding = null
        super.onDestroyView()
        executor.execute {
            detector?.clearObjectDetector()
            embedder?.close()
        }
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun setUpCamera() {
        val context = context ?: return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val b = _binding ?: return@addListener
            if (!isAdded) return@addListener
            val provider = future.get()
            val selector = CameraSelector.Builder()
                .requireLensFacing(if (args.front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                .build()
            @Suppress("DEPRECATION")
            val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }
            provider.unbindAll()
            try {
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
                preview.setSurfaceProvider(b.viewFinder.surfaceProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                showStatus(getString(R.string.camera_unavailable), speakIt = true)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Executor thread.
    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val frame: Bitmap
        val rotation: Int
        proxy.use {
            if (finished || SystemClock.uptimeMillis() - lastSampleAt < SAMPLE_GAP_MS) return
            frame = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
            frame.copyPixelsFromBuffer(it.planes[0].buffer)
            rotation = it.imageInfo.rotationDegrees
        }
        val detector = detector ?: return
        val embedder = embedder ?: return
        try {
            val upright = frame.upright(rotation)
            val detections = detector.detectImage(upright)?.results?.firstOrNull()?.detections().orEmpty()
                .filter { d ->
                    val c = d.categories()[0]
                    c.score() >= MIN_SCORE && kind.allows(c.categoryName()) &&
                        (lockedLabel == null || c.categoryName() == lockedLabel)
                }
            val w = upright.width.toFloat()
            val h = upright.height.toFloat()
            val candidates = detections.map {
                val box = it.boundingBox()
                Candidate(box.centerX() / w, box.centerY() / h, box.width() * box.height() / (w * h))
            }
            val index = CenterPick.pick(candidates, if (lockedLabel == null) FIRST_MIN_AREA else NEXT_MIN_AREA)
                ?: return warn()
            val box = detections[index].boundingBox()
            val crop = upright.cropBox(box.left, box.top, box.right, box.bottom) ?: return warn()
            vectors += embedder.embed(crop)
            if (lockedLabel == null) lockedLabel = detections[index].categories()[0].categoryName()
            if (photo == null) photo = crop
            guide.offer()
            lastSampleAt = SystemClock.uptimeMillis()
            onSample()
        } catch (e: Exception) {
            Log.w(TAG, "Item sample failed", e)
        }
    }

    // Executor thread.
    private fun onSample() {
        val percent = guide.percent()
        val next = guide.currentStep
        val stepFinished = guide.stepFinished
        val done = guide.done
        if (done) finished = true
        onMain {
            showStatus(null)
            showProgress(percent)
            when {
                done -> save()
                stepFinished && next != null -> {
                    binding.instruction.text = next.instruction
                    speech.speakNow(getString(R.string.enroll_percent, percent) + " " + next.instruction)
                }
            }
        }
    }

    // Executor thread.
    private fun warn() {
        val now = SystemClock.uptimeMillis()
        val speakIt = now - lastWarningAt > WARNING_REPEAT_MS
        if (speakIt) lastWarningAt = now
        onMain { showStatus(getString(R.string.item_enroll_no_object), speakIt) }
    }

    private fun save() {
        val image = photo ?: return
        val label = lockedLabel ?: return
        val collected = vectors.toList()
        val name = args.name
        val itemKind = kind
        binding.instruction.text = getString(R.string.enroll_saving)
        val repository = ItemRepository(requireContext())
        lifecycleScope.launch {
            withContext(NonCancellable) { repository.add(name, itemKind, label, image, collected) }
            val done = getString(R.string.enroll_done, name)
            speech.speakNow(done)
            _binding?.instruction?.text = done
            findNavController().popBackStack(R.id.saved_fragment, false)
        }
    }

    private fun showProgress(percent: Int) {
        val b = _binding ?: return
        b.progress.setProgressCompat(percent, true)
        b.percent.text = "$percent%"
    }

    private fun showStatus(text: String?, speakIt: Boolean = false) {
        val b = _binding ?: return
        if (b.status.text?.toString() != (text ?: "")) b.status.text = text
        if (speakIt && text != null) speech.speakNow(text)
    }

    private fun onMain(block: () -> Unit) {
        activity?.runOnUiThread { if (_binding != null) block() }
    }

    private companion object {
        const val TAG = "ClassroomScanner"
        const val MIN_SCORE = 0.4f
        const val FIRST_MIN_AREA = 0.05f
        const val NEXT_MIN_AREA = 0.01f
        const val SAMPLE_GAP_MS = 400L
        const val WARNING_REPEAT_MS = 3_000L
        const val FIRST_SPEECH_DELAY_MS = 1_000L
    }
}
