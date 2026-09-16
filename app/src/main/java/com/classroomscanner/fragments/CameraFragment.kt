package com.classroomscanner.fragments

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import com.classroomscanner.core.ScanMode
import com.classroomscanner.core.ScanSession
import com.classroomscanner.core.ScanSettings
import com.classroomscanner.databinding.FragmentCameraBinding
import com.classroomscanner.history.AppDatabase
import com.classroomscanner.history.HistoryRepository
import com.classroomscanner.scanlog.ScanLogViewModel
import com.classroomscanner.sensor.CameraFov
import com.classroomscanner.sensor.HeadingProvider
import com.classroomscanner.settings.SettingsStore
import com.classroomscanner.speech.SpeechAnnouncer
import com.google.android.material.color.MaterialColors
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener, HeadingProvider.Listener {

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

    // Set once in onViewCreated before the detector starts, then only read (also on the detector thread).
    private lateinit var settings: ScanSettings
    private lateinit var detectionFilter: DetectionFilter
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

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            findNavController().popBackStack()
            return
        }
        backgroundExecutor.execute {
            if (objectDetectorHelper.isClosed()) {
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
        speech.shutdown()
        _fragmentCameraBinding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
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

        backgroundExecutor = Executors.newSingleThreadExecutor()
        backgroundExecutor.execute {
            objectDetectorHelper = ObjectDetectorHelper(
                currentDelegate = if (settings.compute == Compute.GPU) {
                    ObjectDetectorHelper.DELEGATE_GPU
                } else {
                    ObjectDetectorHelper.DELEGATE_CPU
                },
                currentModel = if (settings.model == ModelChoice.FAST) {
                    ObjectDetectorHelper.MODEL_EFFICIENTDETV0
                } else {
                    ObjectDetectorHelper.MODEL_EFFICIENTDETV2
                },
                context = context,
                objectDetectorListener = this,
                runningMode = RunningMode.LIVE_STREAM
            )
            fragmentCameraBinding.viewFinder.post { setUpCamera() }
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
        b.viewText.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(ScanTextDialog.TAG) == null) {
                ScanTextDialog().show(childFragmentManager, ScanTextDialog.TAG)
            }
        }
        updateBanner()
    }

    /** Full Scan needs the rotation sensor; Live Scan works without it. */
    private fun canStart() = args.mode == ScanMode.LIVE || headingProvider.isAvailable

    private fun startScan() {
        val b = fragmentCameraBinding
        session = ScanSession(args.mode, System.currentTimeMillis())
        relHeading = 0f
        headingProvider.start()
        scanLog.start()

        showRunning(true)
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
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
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
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // Using RGBA 8888 to match how our models work
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor, objectDetectorHelper::detectLivestreamFrame)
            }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
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
            Evaluated(detection, kept, counted = kept && !touchesEdge)
        }
        val overlayLabels = evaluated.map { if (it.kept) it.detection.overlayLabel() else null }
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
                    overlayLabels
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
            if (!backgroundExecutor.isShutdown) {
                backgroundExecutor.execute {
                    objectDetectorHelper.currentDelegate = ObjectDetectorHelper.DELEGATE_CPU
                    objectDetectorHelper.setupObjectDetector()
                    if (!objectDetectorHelper.isClosed()) {
                        activity?.runOnUiThread { _fragmentCameraBinding?.startStop?.isEnabled = canStart() }
                    }
                }
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
            scanLog.add(error)
            if (session == null) b.startStop.isEnabled = false
        }
    }

    /** One detector output: whether it passes the confidence filter and whether it is counted. */
    private class Evaluated(val detection: FrameDetection, val kept: Boolean, val counted: Boolean)

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
    }
}
