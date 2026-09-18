package com.classroomscanner.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.classroomscanner.ObjectDetectorHelper
import com.classroomscanner.R
import com.classroomscanner.color.ColorNamer
import com.classroomscanner.core.BoxGeometry
import com.classroomscanner.core.CameraFacing
import com.classroomscanner.core.ColorPolicy
import com.classroomscanner.core.Compute
import com.classroomscanner.core.DetectionFilter
import com.classroomscanner.core.FrameDetection
import com.classroomscanner.core.ModelChoice
import com.classroomscanner.core.OutlineTracker
import com.classroomscanner.core.ScanMode
import com.classroomscanner.core.StickyNames
import com.classroomscanner.core.VoiceCommand
import com.classroomscanner.guide.VoiceCommandTarget
import com.classroomscanner.core.ScanSession
import com.classroomscanner.core.ScanSettings
import com.classroomscanner.databinding.FragmentCameraBinding
import com.classroomscanner.face.FaceRecognizer
import com.classroomscanner.face.upright
import com.classroomscanner.history.AppDatabase
import com.classroomscanner.history.HistoryRepository
import com.classroomscanner.outline.ObjectOutliner
import com.classroomscanner.items.ItemRecognizer
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.items.cropBox
import com.classroomscanner.people.PeopleRepository
import com.classroomscanner.scanlog.ScanLogViewModel
import com.classroomscanner.sensor.CameraFov
import com.classroomscanner.sensor.HeadingProvider
import com.classroomscanner.settings.SettingsStore
import com.classroomscanner.speech.SpeechAnnouncer
import com.classroomscanner.vision.CodeReader
import com.classroomscanner.vision.SceneClassifier
import com.google.android.material.color.MaterialColors
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

class CameraFragment : Fragment(), VoiceCommandTarget, ObjectDetectorHelper.DetectorListener, HeadingProvider.Listener {

    private val args: CameraFragmentArgs by navArgs()
    private val scanLog: ScanLogViewModel by activityViewModels()

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    private lateinit var headingProvider: HeadingProvider
    private lateinit var speech: SpeechAnnouncer
    private lateinit var history: HistoryRepository

    // Set in onViewCreated before the detector starts; `settings` and `hfov` also change on the main
    // thread when the camera is switched, and are read on the detector thread.
    @Volatile
    private lateinit var settings: ScanSettings
    private lateinit var detectionFilter: DetectionFilter
    @Volatile
    private var hfov = CameraFov.FALLBACK_DEG

    // Main thread only.
    private var session: ScanSession? = null
    private var tooFast = false
    private var compassLow = false
    private var lastSlowDownSpokenAt = 0L

    // Written on the main thread, read on the detector thread.
    @Volatile
    private var relHeading = 0f

    @Volatile
    private var gpuFallbackStarted = false

    // Created on the detector thread, used on the MediaPipe result thread.
    @Volatile
    private var faceRecognizer: FaceRecognizer? = null
    @Volatile
    private var itemRecognizer: ItemRecognizer? = null

    /** Loaded the first time someone asks what something is. Detector thread only. */
    @Volatile private var classifier: SceneClassifier? = null
    @Volatile private var codes: CodeReader? = null

    // Name decisions for tracked objects; MediaPipe result thread only.
    private val stickyNames = StickyNames()
    private var nameFrames = 0
    @Volatile private var resetNames = false

    /** What the camera saw last, for the "who is this" and "what is this" commands. */
    @Volatile private var lastLook: Look? = null

    // Object shapes: segmentation runs on its own thread, one frame at a time.
    private lateinit var outlineExecutor: ExecutorService
    @Volatile
    private var outliner: ObjectOutliner? = null
    private val outlineBusy = AtomicBoolean(false)
    private val outlineTracker = OutlineTracker()

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            findNavController().popBackStack()
            return
        }
        backgroundExecutor.execute {
            if (objectDetectorHelper.isClosed()) {
                if (gpuFallbackStarted) objectDetectorHelper.currentDelegate = ObjectDetectorHelper.DELEGATE_CPU
                objectDetectorHelper.setupObjectDetector()
            }
            if (!objectDetectorHelper.isClosed()) {
                activity?.runOnUiThread { _fragmentCameraBinding?.startStop?.isEnabled = canStart() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopScan()
        if (this::objectDetectorHelper.isInitialized) {
            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }
    }

    override fun onDestroyView() {
        speech.shutdownWhenIdle()
        _fragmentCameraBinding = null
        super.onDestroyView()
        val recognizer = faceRecognizer
        faceRecognizer = null
        val items = itemRecognizer
        itemRecognizer = null
        val shapes = outliner
        outliner = null
        outlineExecutor.execute { shapes?.close() }
        outlineExecutor.shutdown()
        val guesser = classifier
        classifier = null
        val reader = codes
        codes = null
        backgroundExecutor.execute {
            recognizer?.close()
            items?.close()
            guesser?.close()
            reader?.close()
        }
        backgroundExecutor.shutdown()
        if (!backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            Log.w(TAG, "Detector thread still busy after 2 s")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        settings = SettingsStore(context).load()
        detectionFilter = DetectionFilter(settings.minScore)
        headingProvider = HeadingProvider(context, this)
        speech = SpeechAnnouncer(context).apply { muted = !settings.speechOn }
        history = HistoryRepository(AppDatabase.get(context).scanDao())
        hfov = CameraFov.portraitHorizontalFov(context, settings.camera)
        Log.i(TAG, "Settings: $settings, horizontal FOV: $hfov")
        gpuFallbackStarted = false
        if (savedInstanceState == null) scanLog.start()

        outlineExecutor = Executors.newSingleThreadExecutor()
        outlineExecutor.execute { outliner = loadOutliner(context) }

        backgroundExecutor = Executors.newSingleThreadExecutor()
        backgroundExecutor.execute {
            objectDetectorHelper = ObjectDetectorHelper(
                currentDelegate = delegateOf(settings.compute),
                currentModel = modelOf(settings.model),
                context = context,
                objectDetectorListener = this,
                runningMode = RunningMode.LIVE_STREAM
            )
            faceRecognizer = loadRecognizer(context)
            itemRecognizer = loadItemRecognizer(context)
            val b = _fragmentCameraBinding
            if (b == null) {
                // The view is gone already; close the detector we just opened.
                objectDetectorHelper.clearObjectDetector()
            } else {
                b.viewFinder.post { if (_fragmentCameraBinding != null) setUpCamera() }
            }
        }

        fragmentCameraBinding.overlay.setRunningMode(RunningMode.LIVE_STREAM)
        fragmentCameraBinding.overlay.mirrored = settings.camera == CameraFacing.FRONT
        initScanControls()
    }

    private fun initScanControls() {
        val b = fragmentCameraBinding
        b.modeLabel.setText(if (args.mode == ScanMode.FULL) R.string.mode_full else R.string.mode_live)
        b.objectCount.text = getString(R.string.objects_count, 0)
        b.startStop.isEnabled = canStart()
        b.startStop.setOnClickListener { if (session == null) startScan() else stopScan() }
        b.switchCamera.setOnClickListener { switchCamera() }
        b.viewText.setOnClickListener {
            if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag(ScanTextDialog.TAG) == null) {
                ScanTextDialog().showNow(childFragmentManager, ScanTextDialog.TAG)
            }
        }
        updateBanner()
    }

    /** Full Scan needs the rotation sensor; Live Scan works without it. */
    private fun canStart() = args.mode == ScanMode.LIVE || headingProvider.isAvailable

    /** Flips between the back and front camera between scans and remembers the choice. */
    private fun switchCamera() {
        if (session != null || cameraProvider == null) return
        val context = context ?: return
        val b = _fragmentCameraBinding ?: return
        val facing = if (settings.camera == CameraFacing.FRONT) CameraFacing.BACK else CameraFacing.FRONT
        settings = settings.copy(camera = facing)
        SettingsStore(context).save(settings)
        hfov = CameraFov.portraitHorizontalFov(context, facing)
        b.overlay.clear()
        outlineTracker.replace(emptyList())
        resetNames = true
        b.overlay.mirrored = facing == CameraFacing.FRONT
        say(getString(if (facing == CameraFacing.FRONT) R.string.camera_now_front else R.string.camera_now_back))
        bindCameraUseCases()
    }

    private fun startScan() {
        val b = fragmentCameraBinding
        session = ScanSession(args.mode, System.currentTimeMillis())
        relHeading = 0f
        headingProvider.start()
        // A finished earlier scan is cleared; notes from before the first scan (like the GPU message) stay.
        if (scanLog.state.value.summary != null) scanLog.start()

        showRunning(true)
        b.switchCamera.isEnabled = false
        b.coverageRing.reset()
        b.objectCount.text = getString(R.string.objects_count, 0)
        say(getString(if (args.mode == ScanMode.FULL) R.string.hint_full else R.string.hint_live))
    }

    private fun stopScan() {
        val s = session ?: return
        session = null
        headingProvider.stop()
        tooFast = false
        compassLow = false

        val result = s.finish()
        speech.speakNow(result.summaryText)
        scanLog.finish(result.summaryText)
        history.saveDetached(result)
        Log.i(TAG, "Scan finished: ${result.summaryText}")

        _fragmentCameraBinding?.let { b ->
            b.announcement.text = result.summaryText
            showRunning(false)
            b.switchCamera.isEnabled = true
        }
        updateBanner()
    }

    /** Shows [text], writes it to the scan log and speaks it (unless speech is off). */
    private fun say(text: String) {
        _fragmentCameraBinding?.announcement?.text = text
        scanLog.add(text)
        speech.announce(text)
    }

    private fun updateBanner() {
        val b = _fragmentCameraBinding ?: return
        val text = when {
            !headingProvider.isAvailable -> getString(R.string.banner_no_compass)
            tooFast -> getString(R.string.banner_slow_down)
            compassLow -> getString(R.string.banner_calibrate)
            else -> null
        }
        if (b.banner.text?.toString() != text) b.banner.text = text
        b.bannerCard.isVisible = text != null
    }

    /** Start is a filled primary button; Stop turns it red so the running state is obvious. */
    private fun showRunning(running: Boolean) {
        val button = fragmentCameraBinding.startStop
        val container = if (running) com.google.android.material.R.attr.colorError else com.google.android.material.R.attr.colorPrimary
        val content = if (running) com.google.android.material.R.attr.colorOnError else com.google.android.material.R.attr.colorOnPrimary
        val onColor = MaterialColors.getColor(button, content)
        button.text = getString(if (running) R.string.stop else R.string.start)
        button.setIconResource(if (running) R.drawable.ic_stop_24 else R.drawable.ic_play_24)
        button.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(button, container))
        button.setTextColor(onColor)
        button.iconTint = ColorStateList.valueOf(onColor)
    }

    private fun updateScanUi(s: ScanSession) {
        val b = _fragmentCameraBinding ?: return
        b.coverageRing.setState(s.coverageSnapshot(), relHeading, s.coveragePercent())
        b.objectCount.text = getString(R.string.objects_count, s.confirmedCount())
    }

    override fun onHeading(relHeading: Float, speedDegPerSec: Float) {
        this.relHeading = relHeading
        val s = session ?: return
        s.onHeading(relHeading)

        val now = System.currentTimeMillis()
        tooFast = speedDegPerSec > MAX_SPEED_DEG_PER_SEC
        if (tooFast && now - lastSlowDownSpokenAt > SLOW_DOWN_REPEAT_MS) {
            scanLog.add(getString(R.string.banner_slow_down))
            speech.announce(getString(R.string.banner_slow_down))
            lastSlowDownSpokenAt = now
        }
        updateBanner()
        updateScanUi(s)
        if (s.shouldAutoStop(now)) stopScan()
    }

    override fun onAccuracyLow(low: Boolean) {
        compassLow = low
        updateBanner()
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val context = context ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                if (_fragmentCameraBinding == null || !isAdded) return@addListener
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    // Declare and bind preview and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val binding = _fragmentCameraBinding ?: return
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val lensFacing = if (settings.camera == CameraFacing.FRONT) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        // Using RGBA 8888 to match how our models work
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor, objectDetectorHelper::detectLivestreamFrame)
            }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            // A camera switch may follow a failed bind; allow Start again once the detector is open.
            if (this::objectDetectorHelper.isInitialized && !objectDetectorHelper.isClosed()) {
                binding.startStop.isEnabled = canStart()
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            binding.announcement.text = getString(R.string.camera_unavailable)
            scanLog.add(getString(R.string.camera_unavailable))
            binding.startStop.isEnabled = false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rotation = _fragmentCameraBinding?.viewFinder?.display?.rotation ?: return
        imageAnalyzer?.targetRotation = rotation
    }

    // Runs on the MediaPipe result thread.
    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        val result = resultBundle.results[0]
        val heading = relHeading
        val frame = resultBundle.frame
        val frameStats = if (settings.colorsOn) frame?.let { ColorNamer.frameStats(it) } else null

        val evaluated = result.detections().map { d ->
            val box = d.boundingBox()
            val center = BoxGeometry.horizontalCenter(
                box.left, box.top, box.right, box.bottom,
                resultBundle.inputImageWidth, resultBundle.inputImageHeight, resultBundle.inputImageRotation
            )
            val category = d.categories()[0]
            val label = category.categoryName()
            val kept = detectionFilter.keep(label, category.score())
            val detection = FrameDetection(
                label = label,
                angle = BoxGeometry.objectAngle(heading, center, hfov, settings.camera),
                color = if (kept && frame != null && frameStats != null && ColorPolicy.hasColor(label)) {
                    ColorNamer.name(frame, box, frameStats)
                } else {
                    null
                }
            )
            val touchesEdge = BoxGeometry.touchesOneSideEdge(
                box.left, box.top, box.right, box.bottom,
                resultBundle.inputImageWidth, resultBundle.inputImageHeight, resultBundle.inputImageRotation
            )
            val upright = BoxGeometry.toUpright(
                box.left, box.top, box.right, box.bottom,
                resultBundle.inputImageWidth, resultBundle.inputImageHeight, resultBundle.inputImageRotation
            )
            Evaluated(detection, kept, counted = kept && !touchesEdge, uprightBox = upright)
        }.let { nameSavedThings(it, frame, result.detections(), resultBundle.inputImageRotation) }
        val overlayLabels = evaluated.map { if (it.kept) it.detection.overlayLabel() else null }
        val rawBoxes = result.detections().map {
            val box = it.boundingBox()
            floatArrayOf(box.left, box.top, box.right, box.bottom)
        }
        val classLabels = result.detections().map { it.categories()[0].categoryName() }
        val detections = result.detections()
        requestOutlines(frame, resultBundle.inputImageRotation, evaluated, rawBoxes, classLabels)
        val outlines = evaluated.mapIndexed { i, e ->
            if (e.kept) outlineTracker.lookup(classLabels[i], rawBoxes[i]) else null
        }
        lastLook = Look(
            frame,
            resultBundle.inputImageRotation,
            evaluated.indices.filter { evaluated[it].kept }.map { i ->
                val box = detections[i].boundingBox()
                Seen(
                    label = evaluated[i].detection.label,
                    isName = evaluated[i].detection.isName,
                    color = evaluated[i].detection.color,
                    rawBox = floatArrayOf(box.left, box.top, box.right, box.bottom),
                    uprightBox = evaluated[i].uprightBox,
                    centerX = BoxGeometry.horizontalCenter(
                        box.left, box.top, box.right, box.bottom,
                        resultBundle.inputImageWidth, resultBundle.inputImageHeight,
                        resultBundle.inputImageRotation,
                    ),
                )
            },
        )
        val countedDetections = evaluated.filter { it.counted }.map { it.detection }
        logInferenceTime(resultBundle.inferenceTime)

        activity?.runOnUiThread {
            val b = _fragmentCameraBinding ?: return@runOnUiThread
            if (isAdded) {
                b.overlay.setResults(
                    result,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    resultBundle.inputImageRotation,
                    overlayLabels,
                    outlines
                )
            }
            b.overlay.invalidate()

            val s = session ?: return@runOnUiThread
            s.onFrame(countedDetections).forEach(::say)
            updateScanUi(s)
            if (s.shouldAutoStop(System.currentTimeMillis())) stopScan()
        }
    }

    // Detector init errors arrive on the detector thread; live-frame errors on the MediaPipe thread.
    override fun onError(error: String, errorCode: Int) {
        if (errorCode == ObjectDetectorHelper.GPU_ERROR && settings.compute == Compute.GPU && !gpuFallbackStarted) {
            gpuFallbackStarted = true
            Log.w(TAG, "GPU detector failed, falling back to CPU: $error")
            try {
                backgroundExecutor.execute {
                    objectDetectorHelper.currentDelegate = ObjectDetectorHelper.DELEGATE_CPU
                    if (objectDetectorHelper.isClosed()) {
                        objectDetectorHelper.setupObjectDetector()
                    }
                    if (!objectDetectorHelper.isClosed()) {
                        activity?.runOnUiThread { _fragmentCameraBinding?.startStop?.isEnabled = canStart() }
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                Log.w(TAG, "Scanner closed before GPU fallback", e)
            }
            activity?.runOnUiThread {
                if (_fragmentCameraBinding == null) return@runOnUiThread
                val message = getString(R.string.gpu_fallback)
                fragmentCameraBinding.announcement.text = message
                scanLog.add(message)
            }
            return
        }
        activity?.runOnUiThread {
            val b = _fragmentCameraBinding ?: return@runOnUiThread
            Log.e(TAG, error)
            b.announcement.text = error
            if (scanLog.state.value.entries.lastOrNull()?.text != error) scanLog.add(error)
            if (session == null) b.startStop.isEnabled = false
        }
    }

    /** One kept detection of the last frame, with everything the app knows about it. */
    private class Seen(
        val label: String,
        val isName: Boolean,
        val color: String?,
        val rawBox: FloatArray,
        val uprightBox: FloatArray,
        val centerX: Float,
    )

    /** The last frame with its kept detections. */
    private class Look(val frame: Bitmap?, val rotation: Int, val seen: List<Seen>)

    /** Spoken commands for the scanner. Main thread. */
    override fun onVoiceCommand(command: VoiceCommand): Boolean {
        val b = _fragmentCameraBinding ?: return false
        return when (command) {
            VoiceCommand.Start -> {
                if (session == null && b.startStop.isEnabled) startScan() else say(getString(R.string.hint_running))
                true
            }
            VoiceCommand.Stop -> {
                if (session != null) {
                    stopScan()
                    true
                } else {
                    false
                }
            }
            VoiceCommand.SwitchCamera -> {
                switchCamera()
                true
            }
            VoiceCommand.ReadText -> {
                b.viewText.performClick()
                true
            }
            VoiceCommand.Repeat -> {
                if (!speech.repeatLast()) say(getString(R.string.hint_idle))
                true
            }
            VoiceCommand.IdentifyPerson -> {
                identify(peopleOnly = true)
                true
            }
            VoiceCommand.IdentifyThing -> {
                identify(peopleOnly = false)
                true
            }
            else -> false
        }
    }

    /**
     * Answers "who is this" and "what is this" about the thing in the middle of the view: a saved
     * name when one matches, otherwise the object with its color.
     */
    private fun identify(peopleOnly: Boolean) {
        val look = lastLook
        val candidates = look?.seen?.filter { !peopleOnly || it.label == PERSON || it.isName }.orEmpty()
        if (look == null || (candidates.isEmpty() && peopleOnly)) {
            speech.speakNow(getString(if (peopleOnly) R.string.identify_no_person else R.string.identify_nothing))
            return
        }
        if (candidates.isEmpty()) {
            // The detector saw nothing it can name; the thousand-class model still can.
            guessMiddle(look)
            return
        }
        val target = candidates.minByOrNull { kotlin.math.abs(it.centerX - 0.5f) } ?: return
        if (target.isName) {
            speech.speakNow(getString(R.string.identify_is, target.label))
            return
        }
        val fallback = target.color?.let { "${'$'}it ${'$'}{target.label}" } ?: target.label
        val frame = look.frame
        if (frame == null) {
            speech.speakNow(getString(R.string.identify_is, fallback))
            return
        }
        try {
            backgroundExecutor.execute {
                val name = lookUpName(target, frame, look.rotation)
                activity?.runOnUiThread {
                    if (_fragmentCameraBinding != null) {
                        speech.speakNow(getString(R.string.identify_is, name ?: fallback))
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            speech.speakNow(getString(R.string.identify_is, fallback))
        }
    }

    /**
     * Answers with the 1000-class model when the COCO detector had no box: it names doors, stairs,
     * windows, food and much else the eighty COCO classes leave out.
     */
    private fun guessMiddle(look: Look) {
        val frame = look.frame
        if (frame == null) {
            speech.speakNow(getString(R.string.identify_nothing))
            return
        }
        try {
            backgroundExecutor.execute {
                var code: String? = null
                val guess = try {
                    val middle = frame.cropBox(
                        frame.width * 0.25f, frame.height * 0.25f,
                        frame.width * 0.75f, frame.height * 0.75f,
                    )?.upright(look.rotation)
                    // A QR code or a barcode says more than any guess about the picture.
                    code = middle?.let { codeReader().read(it) }
                    if (code == null) middle?.let { sceneClassifier()?.name(it) } else null
                } catch (e: Exception) {
                    Log.w(TAG, "Guessing failed", e)
                    null
                }
                activity?.runOnUiThread {
                    if (_fragmentCameraBinding == null) return@runOnUiThread
                    speech.speakNow(
                        when {
                            code != null -> getString(R.string.code_found, code)
                            guess != null -> getString(R.string.identify_maybe, guess)
                            else -> getString(R.string.identify_nothing)
                        }
                    )
                }
            }
        } catch (e: RejectedExecutionException) {
            speech.speakNow(getString(R.string.identify_nothing))
        }
    }

    /** Detector thread: the code reader is kept once it has been made. */
    private fun codeReader(): CodeReader = codes ?: CodeReader().also { codes = it }

    /** Detector thread: the classifier is only loaded when it is first needed. */
    private fun sceneClassifier(): SceneClassifier? {
        classifier?.let { return it }
        val context = context?.applicationContext ?: return null
        return try {
            SceneClassifier(context).also { classifier = it }
        } catch (e: Exception) {
            Log.w(TAG, "Scene classifier unavailable", e)
            null
        }
    }

    /** Detector thread: compares the thing in the middle with saved people and items. */
    private fun lookUpName(target: Seen, frame: Bitmap, rotation: Int): String? {
        try {
            if (target.label == PERSON) {
                val faces = faceRecognizer?.recognize(frame.upright(rotation)).orEmpty()
                val (l, t, r, b) = target.uprightBox.toList()
                return faces.firstOrNull { it.centerX in l..r && it.centerY in t..b }?.match?.name
            }
            val crop = frame.cropBox(target.rawBox[0], target.rawBox[1], target.rawBox[2], target.rawBox[3])
                ?.upright(rotation) ?: return null
            return itemRecognizer?.match(crop, null)?.name
        } catch (e: Exception) {
            Log.w(TAG, "Identify failed", e)
            return null
        }
    }

    /** One detector output: whether it passes the confidence filter and whether it is counted. */
    private class Evaluated(
        val detection: FrameDetection,
        val kept: Boolean,
        val counted: Boolean,
        /** The box in upright-image pixels: left, top, right, bottom. */
        val uprightBox: FloatArray,
    )


    /** Starts shape finding for the largest kept boxes unless a run is still going. Result thread. */
    private fun requestOutlines(
        frame: Bitmap?,
        rotation: Int,
        evaluated: List<Evaluated>,
        boxes: List<FloatArray>,
        labels: List<String>,
    ) {
        val shapes = outliner ?: return
        if (frame == null) return
        val picked = evaluated.indices
            .filter { evaluated[it].kept }
            .sortedByDescending { (boxes[it][2] - boxes[it][0]) * (boxes[it][3] - boxes[it][1]) }
            .take(MAX_OUTLINES)
        if (picked.isEmpty() || !outlineBusy.compareAndSet(false, true)) return
        try {
            outlineExecutor.execute {
                try {
                    val segments = shapes.outlineRaw(frame, rotation, picked.map { boxes[it] })
                    outlineTracker.replace(
                        picked.indices.mapNotNull { k ->
                            segments[k]?.let { OutlineTracker.Entry(labels[picked[k]], boxes[picked[k]], it) }
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Outline failed", e)
                } finally {
                    outlineBusy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            outlineBusy.set(false)
        }
    }

    /** Null when the segmentation model cannot start; boxes are drawn instead. */
    private fun loadOutliner(context: Context): ObjectOutliner? = try {
        ObjectOutliner(context)
    } catch (e: Exception) {
        Log.w(TAG, "Object outlines unavailable", e)
        null
    }

    /**
     * Gives kept detections the names of saved people and items. Objects are followed across frames
     * and a name, once decided, stays with its object, so labels do not flip.
     * Runs on the MediaPipe result thread; recognition runs on every [NAME_EVERY]th frame and only
     * for objects without a decided name.
     */
    private fun nameSavedThings(
        evaluated: List<Evaluated>,
        frame: Bitmap?,
        detections: List<com.google.mediapipe.tasks.components.containers.Detection>,
        rotation: Int,
    ): List<Evaluated> {
        val faces = faceRecognizer
        val items = itemRecognizer
        if (faces == null && items == null) return evaluated
        if (resetNames) {
            resetNames = false
            stickyNames.clear()
        }
        val kept = evaluated.indices.filter { evaluated[it].kept }
        val ids = stickyNames.track(
            kept.map { if (evaluated[it].detection.label == PERSON) PERSON else THING },
            kept.map { evaluated[it].uprightBox },
        )
        val trackOf = kept.zip(ids).toMap()
        if (frame != null && ++nameFrames % NAME_EVERY == 0) {
            val undecided = kept.filter { !stickyNames.isDecided(trackOf.getValue(it)) }
            val people = undecided.filter { evaluated[it].detection.label == PERSON }
            if (faces != null && people.isNotEmpty()) votePeople(people, trackOf, evaluated, faces, frame, rotation)
            val things = undecided.filter { evaluated[it].detection.label != PERSON }
            if (items != null && things.isNotEmpty()) voteItems(things, trackOf, detections, items, frame, rotation)
        }
        return evaluated.mapIndexed { i, e ->
            val name = trackOf[i]?.let { stickyNames.nameOf(it) } ?: return@mapIndexed e
            Evaluated(e.detection.copy(label = name, color = null, isName = true), e.kept, e.counted, e.uprightBox)
        }
    }

    private fun votePeople(
        people: List<Int>,
        trackOf: Map<Int, Int>,
        evaluated: List<Evaluated>,
        recognizer: FaceRecognizer,
        frame: Bitmap,
        rotation: Int,
    ) {
        val faces = try {
            recognizer.recognize(frame.upright(rotation))
        } catch (e: Exception) {
            Log.w(TAG, "Face recognition failed", e)
            return
        }
        for (i in people) {
            val (l, t, r, b) = evaluated[i].uprightBox.toList()
            val face = faces.firstOrNull { it.centerX in l..r && it.centerY in t..b }
            stickyNames.vote(trackOf.getValue(i), face?.match?.name)
        }
    }

    /** Items are compared by look with every saved item, since the detector may mix up similar classes. */
    private fun voteItems(
        things: List<Int>,
        trackOf: Map<Int, Int>,
        detections: List<com.google.mediapipe.tasks.components.containers.Detection>,
        recognizer: ItemRecognizer,
        frame: Bitmap,
        rotation: Int,
    ) {
        things.sortedByDescending {
            val box = detections[it].boundingBox()
            box.width() * box.height()
        }.take(MAX_ITEM_CROPS).forEach { i ->
            val box = detections[i].boundingBox()
            val crop = frame.cropBox(box.left, box.top, box.right, box.bottom)?.upright(rotation) ?: return@forEach
            val match = try {
                recognizer.match(crop, null)
            } catch (e: Exception) {
                Log.w(TAG, "Item recognition failed", e)
                null
            }
            stickyNames.vote(trackOf.getValue(i), match?.name)
        }
    }

    /** Loads saved faces; null when nobody is saved or the models cannot start. Detector thread. */
    private fun loadRecognizer(context: Context): FaceRecognizer? = try {
        val known = runBlocking { PeopleRepository(context).knownFaces() }
        if (known.isEmpty()) null else FaceRecognizer(context, known)
    } catch (e: Exception) {
        Log.w(TAG, "Face recognition unavailable", e)
        null
    }


    /** Loads saved items; null when none are saved or the model cannot start. Detector thread. */
    private fun loadItemRecognizer(context: Context): ItemRecognizer? = try {
        val known = runBlocking { ItemRepository(context).knownItems() }
        if (known.isEmpty()) null else ItemRecognizer(context, known)
    } catch (e: Exception) {
        Log.w(TAG, "Item recognition unavailable", e)
        null
    }

    private var framesSinceTimingLog = 0

    // Runs on the MediaPipe result thread only.
    private fun logInferenceTime(ms: Long) {
        if (++framesSinceTimingLog >= TIMING_LOG_EVERY) {
            framesSinceTimingLog = 0
            Log.i(TAG, "Inference time: $ms ms")
        }
    }

    private fun FrameDetection.overlayLabel() = color?.let { "$label · $it" } ?: label

    private companion object {
        const val TAG = "ClassroomScanner"
        const val MAX_SPEED_DEG_PER_SEC = 60f
        const val SLOW_DOWN_REPEAT_MS = 5_000L
        const val TIMING_LOG_EVERY = 30
        const val NAME_EVERY = 3
        const val THING = "thing"
        const val MAX_ITEM_CROPS = 3
        const val MAX_OUTLINES = 5
        const val PERSON = "person"
    }
}
