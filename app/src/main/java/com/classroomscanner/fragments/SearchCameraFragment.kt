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
import com.classroomscanner.core.OutlineTracker
import com.classroomscanner.outline.ObjectOutliner
import com.classroomscanner.core.SearchGuide
import com.classroomscanner.core.SearchTracker
import com.classroomscanner.core.StickyNames
import com.classroomscanner.core.VoiceCommand
import com.classroomscanner.guide.VoiceCommandTarget
import com.classroomscanner.databinding.FragmentSearchCameraBinding
import com.classroomscanner.face.FaceRecognizer
import com.classroomscanner.face.upright
import com.classroomscanner.items.ItemRecognizer
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.items.cropBox
import com.classroomscanner.people.PeopleRepository
import com.classroomscanner.search.Beeper
import com.classroomscanner.settings.SettingsStore
import com.classroomscanner.speech.SpeechAnnouncer
import com.google.mediapipe.tasks.components.containers.Detection
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Looks for one target (saved person, saved item or COCO label) and guides the user to it with
 * speech, beeps and vibration. Detection results arrive on the MediaPipe result thread.
 */
class SearchCameraFragment : Fragment(), VoiceCommandTarget, ObjectDetectorHelper.DetectorListener {

    private val args: SearchCameraFragmentArgs by navArgs()
    private var _binding: FragmentSearchCameraBinding? = null
    private val binding get() = _binding!!

    private lateinit var executor: ExecutorService
    private lateinit var detector: ObjectDetectorHelper
    private lateinit var speech: SpeechAnnouncer
    private lateinit var beeper: Beeper
    private lateinit var tracker: SearchTracker

    @Volatile private var matcher: Closeable? = null

    // The target's shape is found on its own thread, one frame at a time.
    private lateinit var outlineExecutor: ExecutorService
    @Volatile private var outliner: ObjectOutliner? = null
    private val outlineBusy = AtomicBoolean(false)
    private val outlineTracker = OutlineTracker()
    @Volatile private var failed = false
    private var front = false
    private var wasCentered = false
    private var lastName: String? = null

    // Name decisions for tracked objects; MediaPipe result thread only.
    private val stickyNames = StickyNames()
    @Volatile private var resetNames = false

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
            resetNames = true
            bindCamera()
        }
        view.postDelayed({
            if (_binding != null) {
                speech.speakNow(getString(R.string.searching_for, spokenName()) + ". " + getString(R.string.search_start_hint))
            }
        }, FIRST_SPEECH_DELAY_MS)

        outlineExecutor = Executors.newSingleThreadExecutor()
        outlineExecutor.execute {
            outliner = try {
                ObjectOutliner(context)
            } catch (e: Exception) {
                Log.w(TAG, "Search outline unavailable", e)
                null
            }
        }
        executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val settings = SettingsStore(context).load()
            detector = ObjectDetectorHelper(
                threshold = MIN_SCORE,
                currentDelegate = delegateOf(settings.compute),
                currentModel = modelOf(settings.model),
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
        val shapes = outliner
        outliner = null
        outlineExecutor.execute { shapes?.close() }
        outlineExecutor.shutdown()
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun spokenName(): String =
        if (args.targetKind == SearchFragment.KIND_LABEL) "a ${args.targetName}" else args.targetName

    /**
     * Person: the target's faces. Item: the target's embeddings. Label: every saved person or item
     * of that label, so found things are named; null when none are saved. Executor thread.
     */
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
            else -> if (args.targetLabel == PERSON) {
                val faces = runBlocking { PeopleRepository(context).knownFaces() }
                if (faces.isEmpty()) null else FaceRecognizer(context, faces)
            } else {
                val items = runBlocking { ItemRepository(context).knownItems() }
                    .filter { it.label == args.targetLabel }
                if (items.isEmpty()) null else ItemRecognizer(context, items)
            }
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
            b.overlay.setRunningMode(RunningMode.LIVE_STREAM)
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
        val found = if (failed) emptyMap() else findTarget(detections, frame, w, h, rotation)
        val matches = found.keys

        val best = matches.maxByOrNull {
            val box = detections[it].boundingBox()
            box.width() * box.height()
        }
        val centerX = best?.let {
            val box = detections[it].boundingBox()
            val c = BoxGeometry.horizontalCenter(box.left, box.top, box.right, box.bottom, w, h, rotation)
            if (front) 1f - c else c
        }
        val labels = detections.indices.map { i -> if (i in found) found[i] ?: args.targetName else null }
        val outlines = detections.indices.map { i ->
            if (i != best || frame == null) return@map null
            val box = detections[i].boundingBox()
            val rect = floatArrayOf(box.left, box.top, box.right, box.bottom)
            requestOutline(frame, rotation, rect)
            outlineTracker.lookup(OUTLINE_KEY, rect)
        }
        val bestName = best?.let { found[it] }
        val now = SystemClock.uptimeMillis()

        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            b.overlay.setResults(result, h, w, rotation, labels, outlines)
            b.overlay.invalidate()
            val said = tracker.update(now, centerX)
            val naming = if (bestName != null && bestName != lastName) "That is $bestName." else null
            if (centerX == null) lastName = null else if (bestName != null) lastName = bestName
            listOfNotNull(said, naming).takeIf { it.isNotEmpty() }?.let { showAndSay(it.joinToString(" ")) }
            beeper.setInterval(centerX?.let { SearchGuide.beepIntervalMs(it) })
            val centered = centerX != null && SearchGuide.isCentered(centerX)
            if (centered && !wasCentered) beeper.buzz()
            wasCentered = centered
        }
    }

    /**
     * Detections that are the target, each with a saved name when one was recognized (label search)
     * or null. MediaPipe result thread.
     */
    private fun findTarget(
        detections: List<Detection>,
        frame: Bitmap?,
        w: Int,
        h: Int,
        rotation: Int,
    ): Map<Int, String?> {
        // A saved item is matched by its look, so similar classes (laptop, tv, keyboard) all count.
        val anyLabel = args.targetKind == SearchFragment.KIND_ITEM
        val kept = detections.indices.filter {
            val c = detections[it].categories()[0]
            c.score() >= MIN_SCORE &&
                if (anyLabel) c.categoryName() != PERSON else c.categoryName() == args.targetLabel
        }
        if (kept.isEmpty()) return emptyMap()
        if (resetNames) {
            resetNames = false
            stickyNames.clear()
        }
        val ids = stickyNames.track(
            kept.map { if (detections[it].categories()[0].categoryName() == PERSON) PERSON else THING },
            kept.map {
                val box = detections[it].boundingBox()
                floatArrayOf(box.left, box.top, box.right, box.bottom)
            },
        )
        val trackOf = kept.zip(ids).toMap()
        val undecided = kept.filter { !stickyNames.isDecided(trackOf.getValue(it)) }
        if (undecided.isNotEmpty()) {
            val names = when (val recognizer = matcher) {
                is FaceRecognizer -> faceNames(undecided, recognizer, detections, frame, w, h, rotation)
                is ItemRecognizer -> itemNames(undecided, recognizer, detections, frame, rotation, anyLabel)
                else -> null
            }
            names?.forEach { (i, name) -> stickyNames.vote(trackOf.getValue(i), name) }
        }
        return if (args.targetKind == SearchFragment.KIND_LABEL) {
            kept.associateWith { stickyNames.nameOf(trackOf.getValue(it)) }
        } else {
            kept.filter { stickyNames.nameOf(trackOf.getValue(it)) != null }.associateWith { null }
        }
    }

    /** Saved item names for the largest [indices] whose crops match. */
    private fun itemNames(
        indices: List<Int>,
        recognizer: ItemRecognizer,
        detections: List<Detection>,
        frame: Bitmap?,
        rotation: Int,
        anyLabel: Boolean = false,
    ): Map<Int, String> {
        if (frame == null) return emptyMap()
        val label = if (anyLabel) null else args.targetLabel
        val names = HashMap<Int, String>()
        indices.sortedByDescending {
            val box = detections[it].boundingBox()
            box.width() * box.height()
        }.take(MAX_ITEM_CROPS).forEach { i ->
            val box = detections[i].boundingBox()
            val crop = frame.cropBox(box.left, box.top, box.right, box.bottom)?.upright(rotation) ?: return@forEach
            runCatching { recognizer.match(crop, label) }.getOrNull()?.let { names[i] = it.name }
        }
        return names
    }

    /** Saved person names for person boxes that hold a recognized face. */
    private fun faceNames(
        indices: List<Int>,
        recognizer: FaceRecognizer,
        detections: List<Detection>,
        frame: Bitmap?,
        w: Int,
        h: Int,
        rotation: Int,
    ): Map<Int, String> {
        if (frame == null) return emptyMap()
        val faces = runCatching { recognizer.recognize(frame.upright(rotation)) }.getOrElse {
            Log.w(TAG, "Face search failed", it)
            emptyList()
        }
        if (faces.isEmpty()) return emptyMap()
        val names = HashMap<Int, String>()
        for (i in indices) {
            val box = detections[i].boundingBox()
            val (l, t, r, b) = BoxGeometry.toUpright(box.left, box.top, box.right, box.bottom, w, h, rotation).toList()
            faces.firstOrNull { it.centerX in l..r && it.centerY in t..b }?.let { names[i] = it.match.name }
        }
        return names
    }

    /** Starts shape finding for the target box unless a run is still going. MediaPipe result thread. */
    private fun requestOutline(frame: Bitmap, rotation: Int, rect: FloatArray) {
        val shapes = outliner ?: return
        if (!outlineBusy.compareAndSet(false, true)) return
        try {
            outlineExecutor.execute {
                try {
                    val points = shapes.outlineRaw(frame, rotation, listOf(rect))[0]
                    outlineTracker.replace(listOfNotNull(points?.let { OutlineTracker.Entry(OUTLINE_KEY, rect, it) }))
                } catch (e: Exception) {
                    Log.w(TAG, "Search outline failed", e)
                } finally {
                    outlineBusy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            outlineBusy.set(false)
        }
    }

    /** Spoken commands for the search screen. Main thread. */
    override fun onVoiceCommand(command: VoiceCommand): Boolean = when (command) {
        VoiceCommand.SwitchCamera -> {
            _binding?.switchCamera?.performClick()
            true
        }
        VoiceCommand.Repeat -> {
            if (!speech.repeatLast()) speech.speakNow(getString(R.string.searching_for, spokenName()))
            true
        }
        else -> false
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
        const val PERSON = "person"
        const val OUTLINE_KEY = "target"
        const val THING = "thing"
    }
}
