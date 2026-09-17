package com.classroomscanner.fragments

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.classroomscanner.ObjectDetectorHelper
import com.classroomscanner.R
import com.classroomscanner.core.BoxGeometry
import com.classroomscanner.core.SearchGuide
import com.classroomscanner.core.SearchTracker
import com.classroomscanner.databinding.FragmentSearchCameraBinding
import com.classroomscanner.face.FaceRecognizer
import com.classroomscanner.face.upright
import com.classroomscanner.items.ItemRecognizer
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.items.cropBox
import com.classroomscanner.people.PeopleRepository
import com.classroomscanner.search.Beeper
import com.classroomscanner.speech.SpeechAnnouncer
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Looks for one target (saved person, saved item or COCO label) and guides the user to it with
 * speech, beeps and vibration. Detection results arrive on the MediaPipe result thread.
 */
class SearchCameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val args: SearchCameraFragmentArgs by navArgs()
    private var _binding: FragmentSearchCameraBinding? = null
    private val binding get() = _binding!!

    private lateinit var executor: ExecutorService
    private lateinit var detector: ObjectDetectorHelper
    private lateinit var speech: SpeechAnnouncer
    private lateinit var beeper: Beeper
    private lateinit var tracker: SearchTracker

    @Volatile private var matcher: Closeable? = null
    @Volatile private var failed = false
    private var front = false
    private var wasCentered = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext().applicationContext
        front = savedInstanceState?.getBoolean(KEY_FRONT) ?: args.front
        speech = SpeechAnnouncer(context)
        beeper = Beeper(context)
        tracker = SearchTracker(spokenName())
        binding.target.text = getString(R.string.searching_for, spokenName())
        binding.stopButton.setOnClickListener { findNavController().popBackStack() }
        binding.switchCamera.setOnClickListener {
            front = !front
            wasCentered = false
            bindCamera()
        }
        view.postDelayed({
            if (_binding != null) {
                speech.speakNow(getString(R.string.searching_for, spokenName()) + ". " + getString(R.string.search_start_hint))
            }
        }, FIRST_SPEECH_DELAY_MS)

        executor = Executors.newSingleThreadExecutor()
        executor.execute {
            detector = ObjectDetectorHelper(
                threshold = MIN_SCORE,
                currentModel = ObjectDetectorHelper.MODEL_EFFICIENTDETV2,
                runningMode = RunningMode.LIVE_STREAM,
                context = context,
                objectDetectorListener = this,
            )
            matcher = loadMatcher(context)
            if (failed) {
                activity?.runOnUiThread { showAndSay(getString(R.string.search_unavailable)) }
            }
        }
        binding.viewFinder.post { bindCamera() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FRONT, front)
    }

    override fun onDestroyView() {
        beeper.release()
        speech.shutdownWhenIdle()
        _binding = null
        super.onDestroyView()
        val m = matcher
        matcher = null
        executor.execute {
            if (this::detector.isInitialized) detector.clearObjectDetector()
            m?.close()
        }
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun spokenName(): String =
        if (args.targetKind == SearchFragment.KIND_LABEL) "a ${args.targetName}" else args.targetName

    /** Face recognizer (person), item recognizer (item) or nothing (label). Executor thread. */
    private fun loadMatcher(context: Context): Closeable? = try {
        when (args.targetKind) {
            SearchFragment.KIND_PERSON -> {
                val faces = runBlocking { PeopleRepository(context).knownFaces() }
                    .filter { it.personId == args.targetId }
                if (faces.isEmpty()) {
                    failed = true
                    null
                } else {
                    FaceRecognizer(context, faces)
                }
            }
            SearchFragment.KIND_ITEM -> {
                val items = runBlocking { ItemRepository(context).knownItems() }
                    .filter { it.itemId == args.targetId }
                if (items.isEmpty()) {
                    failed = true
                    null
                } else {
                    ItemRecognizer(context, items)
                }
            }
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Search matcher failed", e)
        failed = true
        null
    }

    private fun bindCamera() {
        val context = context ?: return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val b = _binding ?: return@addListener
            if (!isAdded) return@addListener
            val provider = future.get()
            val selector = CameraSelector.Builder()
                .requireLensFacing(if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                .build()
            @Suppress("DEPRECATION")
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(b.viewFinder.display.rotation)
                .build()
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(b.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { a ->
                    a.setAnalyzer(executor) { proxy ->
                        if (this::detector.isInitialized && !detector.isClosed()) {
                            detector.detectLivestreamFrame(proxy)
                        } else {
                            proxy.close()
                        }
                    }
                }
            b.overlay.mirrored = front
            b.overlay.clear()
            provider.unbindAll()
            try {
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
                preview.setSurfaceProvider(b.viewFinder.surfaceProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                showAndSay(getString(R.string.camera_unavailable))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // MediaPipe result thread.
    @SuppressLint("UnsafeOptInUsageError")
    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        val result = resultBundle.results[0]
        val frame = resultBundle.frame
        val w = resultBundle.inputImageWidth
        val h = resultBundle.inputImageHeight
        val rotation = resultBundle.inputImageRotation
        val detections = result.detections()
        val matches = if (failed) emptyList() else findTarget(detections, frame, w, h, rotation)

        val best = matches.maxByOrNull {
            val box = detections[it].boundingBox()
            box.width() * box.height()
        }
        val centerX = best?.let {
            val box = detections[it].boundingBox()
            val c = BoxGeometry.horizontalCenter(box.left, box.top, box.right, box.bottom, w, h, rotation)
            if (front) 1f - c else c
        }
        val labels = detections.indices.map { if (it in matches) args.targetName else null }
        val now = SystemClock.uptimeMillis()

        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            b.overlay.setResults(result, h, w, rotation, labels, null)
            b.overlay.invalidate()
            tracker.update(now, centerX)?.let { showAndSay(it) }
            beeper.setInterval(centerX?.let { SearchGuide.beepIntervalMs(it) })
            val centered = centerX != null && SearchGuide.isCentered(centerX)
            if (centered && !wasCentered) beeper.buzz()
            wasCentered = centered
        }
    }

    /** Indices of detections that are the target. MediaPipe result thread. */
    private fun findTarget(
        detections: List<com.google.mediapipe.tasks.components.containers.Detection>,
        frame: Bitmap?,
        w: Int,
        h: Int,
        rotation: Int,
    ): List<Int> {
        val kept = detections.indices.filter {
            val c = detections[it].categories()[0]
            c.score() >= MIN_SCORE && c.categoryName() == args.targetLabel
        }
        if (kept.isEmpty()) return emptyList()
        return when (args.targetKind) {
            SearchFragment.KIND_LABEL -> kept
            SearchFragment.KIND_ITEM -> {
                val recognizer = matcher as? ItemRecognizer ?: return emptyList()
                if (frame == null) return emptyList()
                kept.sortedByDescending {
                    val box = detections[it].boundingBox()
                    box.width() * box.height()
                }.take(MAX_ITEM_CROPS).filter {
                    val box = detections[it].boundingBox()
                    val crop = frame.cropBox(box.left, box.top, box.right, box.bottom)?.upright(rotation)
                    crop != null && runCatching { recognizer.match(crop, args.targetLabel) }.getOrNull() != null
                }
            }
            SearchFragment.KIND_PERSON -> {
                val recognizer = matcher as? FaceRecognizer ?: return emptyList()
                if (frame == null) return emptyList()
                val faces = runCatching { recognizer.recognize(frame.upright(rotation)) }.getOrElse {
                    Log.w(TAG, "Face search failed", it)
                    emptyList()
                }
                kept.filter { i ->
                    val box = detections[i].boundingBox()
                    val (l, t, r, bottom) = BoxGeometry.toUpright(box.left, box.top, box.right, box.bottom, w, h, rotation).toList()
                    faces.any { it.centerX in l..r && it.centerY in t..bottom }
                }
            }
            else -> emptyList()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e(TAG, error)
        activity?.runOnUiThread { if (_binding != null) showAndSay(getString(R.string.search_unavailable)) }
    }

    private fun showAndSay(text: String) {
        val b = _binding ?: return
        b.announcement.text = text
        speech.speakNow(text)
    }

    private companion object {
        const val TAG = "ClassroomScanner"
        const val MIN_SCORE = 0.4f
        const val MAX_ITEM_CROPS = 3
        const val FIRST_SPEECH_DELAY_MS = 800L
        const val KEY_FRONT = "search_front"
    }
}
