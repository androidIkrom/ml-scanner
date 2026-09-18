package com.classroomscanner.fragments

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.RectF
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
import com.classroomscanner.core.ItemMatcher
import com.classroomscanner.core.ItemStep
import com.classroomscanner.core.OutlineTracker
import com.classroomscanner.databinding.FragmentEnrollBinding
import com.classroomscanner.face.upright
import com.classroomscanner.items.ItemEmbedder
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.items.cropBox
import com.classroomscanner.outline.ObjectOutliner
import com.classroomscanner.settings.SettingsStore
import com.classroomscanner.speech.SpeechAnnouncer
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step 2 of adding a car or object: finds the thing in the middle of the view, collects image
 * embeddings from three spots, then saves it. Model work runs on [executor] only.
 */
class ItemEnrollFragment : Fragment() {

    private val args: ItemEnrollFragmentArgs by navArgs()
    private var _binding: FragmentEnrollBinding? = null
    private val binding get() = _binding!!

    private lateinit var executor: ExecutorService

    // Shapes are found on their own thread so sampling is not slowed down.
    private lateinit var outlineExecutor: ExecutorService
    @Volatile private var outliner: ObjectOutliner? = null
    private val outlineBusy = AtomicBoolean(false)
    private val outlineTracker = OutlineTracker()
    private lateinit var speech: SpeechAnnouncer

    // Executor thread only.
    private var detector: ObjectDetectorHelper? = null
    private var embedder: ItemEmbedder? = null
    private val guide = ItemEnrollmentGuide()
    private val vectors = mutableListOf<FloatArray>()
    private val seenLabels = mutableListOf<String>()
    private var photo: Bitmap? = null
    private var lastSampleAt = 0L
    private var lastFrameAt = 0L
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
        outlineExecutor = Executors.newSingleThreadExecutor()
        outlineExecutor.execute {
            outliner = try {
                ObjectOutliner(context)
            } catch (e: Exception) {
                Log.w(TAG, "Item outline unavailable", e)
                null
            }
        }
        executor.execute {
            try {
                val settings = SettingsStore(context).load()
                detector = ObjectDetectorHelper(
                    threshold = MIN_SCORE,
                    currentDelegate = delegateOf(settings.compute),
                    currentModel = modelOf(settings.model),
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
        binding.overlay.setRunningMode(RunningMode.LIVE_STREAM)
        binding.overlay.mirrored = args.front
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
        val shapes = outliner
        outliner = null
        outlineExecutor.execute { shapes?.close() }
        outlineExecutor.shutdown()
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
            if (finished || SystemClock.uptimeMillis() - lastFrameAt < FRAME_GAP_MS) return
            frame = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
            frame.copyPixelsFromBuffer(it.planes[0].buffer)
            rotation = it.imageInfo.rotationDegrees
        }
        lastFrameAt = SystemClock.uptimeMillis()
        val detector = detector ?: return
        val embedder = embedder ?: return
        try {
            val upright = frame.upright(rotation)
            val result = detector.detectImage(upright)?.results?.firstOrNull() ?: return
            val all = result.detections()
            val allowed = all.indices.filter { i ->
                val c = all[i].categories()[0]
                c.score() >= MIN_SCORE && kind.allows(c.categoryName())
            }
            val w = upright.width.toFloat()
            val h = upright.height.toFloat()
            val candidates = allowed.map {
                val box = all[it].boundingBox()
                Candidate(box.centerX() / w, box.centerY() / h, box.width() * box.height() / (w * h))
            }
            val pick = CenterPick.pick(candidates, if (seenLabels.isEmpty()) FIRST_MIN_AREA else NEXT_MIN_AREA)
            val sampleDue = SystemClock.uptimeMillis() - lastSampleAt >= SAMPLE_GAP_MS
            if (pick == null) {
                onMain { binding.overlay.clear() }
                if (sampleDue) warn()
                return
            }
            val index = allowed[pick]
            val box = all[index].boundingBox()
            val label = all[index].categories()[0].categoryName()
            showTarget(result, upright, index, label, box)
            if (!sampleDue) return
            val crop = upright.cropBox(box.left, box.top, box.right, box.bottom) ?: return warn()
            vectors += embedder.embed(crop)
            seenLabels += label
            if (photo == null) photo = crop
            guide.offer()
            lastSampleAt = SystemClock.uptimeMillis()
            onSample()
        } catch (e: Exception) {
            Log.w(TAG, "Item sample failed", e)
        }
    }

    /**
     * Draws the picked object at once: its last known shape when it still fits, otherwise its box.
     * A new shape is requested in the background. Executor thread.
     */
    private fun showTarget(result: ObjectDetectorResult, upright: Bitmap, index: Int, label: String, box: RectF) {
        val rect = floatArrayOf(box.left, box.top, box.right, box.bottom)
        requestOutline(upright, label, rect)
        val shape = outlineTracker.lookup(label, rect)
        val count = result.detections().size
        val labels = List(count) { if (it == index) label else null }
        val outlines = List(count) { if (it == index) shape else null }
        onMain {
            binding.overlay.setResults(result, upright.height, upright.width, 0, labels, outlines)
            binding.overlay.invalidate()
        }
    }

    private fun requestOutline(upright: Bitmap, label: String, rect: FloatArray) {
        val shapes = outliner ?: return
        if (!outlineBusy.compareAndSet(false, true)) return
        try {
            outlineExecutor.execute {
                try {
                    val points = shapes.outline(upright, listOf(rect))[0]
                    outlineTracker.replace(listOfNotNull(points?.let { OutlineTracker.Entry(label, rect, it) }))
                } catch (e: Exception) {
                    Log.w(TAG, "Item outline failed", e)
                } finally {
                    outlineBusy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            outlineBusy.set(false)
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
        val label = ItemMatcher.mostCommon(seenLabels) ?: return
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
        const val MIN_SCORE = 0.3f
        const val FIRST_MIN_AREA = 0.05f
        const val NEXT_MIN_AREA = 0.01f
        const val SAMPLE_GAP_MS = 400L
        const val FRAME_GAP_MS = 100L
        const val WARNING_REPEAT_MS = 3_000L
        const val FIRST_SPEECH_DELAY_MS = 1_000L
    }
}
