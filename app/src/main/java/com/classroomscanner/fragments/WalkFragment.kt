package com.classroomscanner.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.classroomscanner.ObjectDetectorHelper
import com.classroomscanner.R
import com.classroomscanner.core.Beacon
import com.classroomscanner.core.Hazard
import com.classroomscanner.core.HazardConfirmer
import com.classroomscanner.core.HazardPolicy
import com.classroomscanner.core.DepthObstacles
import com.classroomscanner.core.StickyNames
import com.classroomscanner.core.TrafficLightColor
import com.classroomscanner.core.VoiceCommand
import com.classroomscanner.core.WalkAlerts
import com.classroomscanner.core.WalkGeometry
import com.classroomscanner.core.WalkPhrases
import com.classroomscanner.core.WalkZone
import com.classroomscanner.databinding.FragmentWalkBinding
import com.classroomscanner.face.FaceRecognizer
import com.classroomscanner.face.cropFace
import com.classroomscanner.guide.VoiceCommandTarget
import com.classroomscanner.items.ItemRecognizer
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.items.cropBox
import com.classroomscanner.people.PeopleRepository
import com.classroomscanner.search.Beeper
import com.classroomscanner.speech.SpeechAnnouncer
import com.classroomscanner.walk.ArCamera
import com.classroomscanner.walk.ArProblem
import com.classroomscanner.walk.Compass
import com.classroomscanner.walk.LightColor
import com.classroomscanner.walk.PlaceStore
import com.classroomscanner.walk.SignReader
import com.classroomscanner.walk.StepCounter
import com.classroomscanner.walk.WalkFrame
import com.classroomscanner.walk.toWalkFrame
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Walk mode: the phone watches the way ahead and warns about what is in it. ARCore owns the camera
 * here and gives metric depth, so distances are real. Detection and recognition run on
 * [analysisExecutor]; ARCore frames are read on the GL thread only.
 */
class WalkFragment : Fragment(), VoiceCommandTarget, GLSurfaceView.Renderer {

    private var _binding: FragmentWalkBinding? = null
    private val binding get() = _binding!!

    private lateinit var speech: SpeechAnnouncer
    private lateinit var beeper: Beeper
    private lateinit var compass: Compass
    private lateinit var steps: StepCounter
    private lateinit var places: PlaceStore
    private lateinit var analysisExecutor: ExecutorService
    private val background = com.classroomscanner.walk.BackgroundRenderer()

    private var arCamera: ArCamera? = null
    private val busy = AtomicBoolean(false)
    private var lastAnalysisAt = 0L

    // Analysis thread only.
    private var detector: ObjectDetectorHelper? = null
    private var faceRecognizer: FaceRecognizer? = null
    private var itemRecognizer: ItemRecognizer? = null
    private var signReader: SignReader? = null
    private val alerts = WalkAlerts()
    private val confirmer = HazardConfirmer()
    private val stickyNames = StickyNames()
    private var frameCount = 0
    private var lastGround: String? = null
    private var lastLight: TrafficLightColor? = null
    private var lastSign: String? = null
    private var lastSignAt = 0L

    // Main thread only.
    private var beaconName: String? = null
    private var beaconPlace: Pair<Double, Double>? = null
    private var lastFix: Location? = null
    private var lastBeaconAt = 0L
    private var lastBeaconHour = 0

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startLocation() else say(getString(R.string.walk_no_location))
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWalkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext().applicationContext
        speech = SpeechAnnouncer(context)
        beeper = Beeper(context)
        compass = Compass(context)
        steps = StepCounter(context)
        places = PlaceStore(context)
        analysisExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor.execute { openModels(context) }

        binding.glView.preserveEGLContextOnPause = true
        binding.glView.setEGLContextClientVersion(2)
        binding.glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.glView.setRenderer(this)
        binding.glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        binding.stopButton.setOnClickListener { findNavController().popBackStack() }
        binding.savePlaceButton.setOnClickListener { askPlaceName() }
        binding.guideButton.setOnClickListener { askWhereTo() }

        val problem = ArCamera(requireActivity()).let { camera ->
            val failure = camera.open()
            if (failure == null) arCamera = camera else camera.close()
            failure
        }
        if (problem != null) {
            showProblem(problem)
        } else {
            val camera = arCamera
            val depth = if (camera?.depthSupported == true) {
                getString(R.string.walk_depth_on)
            } else {
                getString(R.string.walk_depth_off)
            }
            binding.status.text = depth
            view.postDelayed({ if (_binding != null) speech.speakNow(getString(R.string.walk_started) + " " + depth) }, START_SPEECH_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (arCamera?.resume() == false) say(getString(R.string.camera_unavailable))
        binding.glView.onResume()
        compass.start()
        steps.start()
    }

    override fun onPause() {
        binding.glView.onPause()
        arCamera?.pause()
        compass.stop()
        steps.stop()
        beeper.setInterval(null)
        super.onPause()
    }

    override fun onDestroyView() {
        beeper.release()
        speech.shutdownWhenIdle()
        stopLocation()
        _binding = null
        super.onDestroyView()
        val camera = arCamera
        arCamera = null
        analysisExecutor.execute {
            detector?.clearObjectDetector()
            faceRecognizer?.close()
            itemRecognizer?.close()
            signReader?.close()
        }
        analysisExecutor.shutdown()
        analysisExecutor.awaitTermination(2, TimeUnit.SECONDS)
        camera?.close()
    }

    // ---- models, analysis thread ----

    private fun openModels(context: Context) {
        try {
            detector = ObjectDetectorHelper(
                threshold = MIN_SCORE,
                currentModel = ObjectDetectorHelper.MODEL_EFFICIENTDETV0,
                runningMode = RunningMode.IMAGE,
                context = context,
            )
            signReader = SignReader()
            val known = runBlocking { PeopleRepository(context).knownFaces() }
            if (known.isNotEmpty()) faceRecognizer = FaceRecognizer(context, known)
            val items = runBlocking { ItemRepository(context).knownItems() }
            if (items.isNotEmpty()) itemRecognizer = ItemRecognizer(context, items)
        } catch (e: Exception) {
            Log.w(TAG, "Walk models failed", e)
            onMain { say(getString(R.string.walk_models_failed)) }
        }
    }

    // ---- GL thread ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        arCamera?.useTexture(background.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val display = activity?.windowManager?.defaultDisplay?.rotation ?: 0
        arCamera?.session?.setDisplayGeometry(display, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frame = arCamera?.update() ?: return
        background.draw(frame)
        val now = SystemClock.uptimeMillis()
        if (now - lastAnalysisAt < ANALYSIS_GAP_MS || !busy.compareAndSet(false, true)) return
        lastAnalysisAt = now
        val walkFrame = frame.toWalkFrame()
        if (walkFrame == null) {
            busy.set(false)
            return
        }
        try {
            analysisExecutor.execute {
                try {
                    analyze(walkFrame)
                } catch (e: Exception) {
                    Log.w(TAG, "Walk analysis failed", e)
                } finally {
                    busy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            busy.set(false)
        }
    }

    // ---- analysis thread ----

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(frame: WalkFrame) {
        val detector = detector ?: return
        val image = frame.image
        val result = detector.detectImage(image)?.results?.firstOrNull() ?: return
        val detections = result.detections()
        val horizon = WalkGeometry.horizonY(frame.pitchDeg, frame.focalPx, image.height)
        frameCount++

        val hazards = ArrayList<Hazard>()
        var nearestAhead: Float? = null
        var light: TrafficLightColor? = null
        val boxes = ArrayList<FloatArray>()
        val labels = ArrayList<String>()
        val metresOf = ArrayList<Float>()

        for (detection in detections) {
            val category = detection.categories()[0]
            val label = category.categoryName()
            if (category.score() < MIN_SCORE) continue
            val box = detection.boundingBox()
            if (label == TRAFFIC_LIGHT) {
                light = LightColor.classify(image, box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
            }
            if (!HazardPolicy.isHazard(label)) continue
            val metres = frame.depth?.metres(
                box.left / image.width, box.top / image.height,
                box.right / image.width, box.bottom / image.height,
            ) ?: WalkGeometry.distanceFromBottom(box.bottom, horizon, frame.focalPx, CAMERA_HEIGHT_M)
            ?: continue
            val zone = WalkZone.of((box.left + box.right) / 2f / image.width)
            hazards += Hazard(label, metres, zone)
            boxes += floatArrayOf(box.left, box.top, box.right, box.bottom)
            labels += label
            metresOf += metres
            if (zone == WalkZone.AHEAD && (nearestAhead == null || metres < nearestAhead!!)) nearestAhead = metres
        }

        // Walls and doors have no class, so the depth image answers for them.
        val blocked = frame.depth?.let {
            DepthObstacles.nearest(it.metresGrid(), it.width, it.height)
        }.orEmpty()
        val named = nameNearest(frame, confirmer.confirmed(hazards), boxes, labels, metresOf)
        val obstacles = blocked.mapNotNull { (zone, metres) ->
            // Only where no known thing already explains what is there.
            if (named.any { it.zone == zone && abs(it.metres - metres) < SAME_THING_M }) {
                null
            } else {
                Hazard(getString(R.string.walk_obstacle), metres, zone)
            }
        }
        val all = named + obstacles
        blocked[WalkZone.AHEAD]?.let { ahead ->
            if (nearestAhead == null || ahead < nearestAhead!!) nearestAhead = ahead
        }
        val alert = alerts.next(SystemClock.uptimeMillis(), all)
        val stepLength = if (steps.available) WalkGeometry.stepLength(BODY_HEIGHT_M) else null
        val groundChanged = frame.ground != null && frame.ground != lastGround
        if (groundChanged) lastGround = frame.ground
        val lightChanged = light != null && light != lastLight
        if (lightChanged) lastLight = light
        val sign = readSign(image)

        onMain {
            when {
                alert != null -> say(WalkPhrases.hazard(alert, stepLength))
                groundChanged -> say(WalkPhrases.ground(lastGround ?: return@onMain))
                lightChanged -> say(WalkPhrases.trafficLight(light ?: return@onMain))
                sign != null -> say(getString(R.string.walk_sign, sign))
            }
            beeper.setInterval(WalkAlerts.beepIntervalMs(nearestAhead))
            if (nearestAhead != null && nearestAhead!! < BUZZ_M) beeper.buzz()
            speakBeacon()
        }
    }

    /** Replaces the nearest hazard's class with a saved name when one matches. Analysis thread. */
    private fun nameNearest(
        frame: WalkFrame,
        hazards: List<Hazard>,
        boxes: List<FloatArray>,
        labels: List<String>,
        metres: List<Float>,
    ): List<Hazard> {
        if (hazards.isEmpty()) return hazards
        val ids = stickyNames.track(
            labels.map { if (it == PERSON) PERSON else THING },
            boxes,
        )
        if (frameCount % NAME_EVERY == 0) {
            val nearest = metres.indices.minByOrNull { metres[it] } ?: return hazards
            if (!stickyNames.isDecided(ids[nearest])) {
                val name = lookUpName(frame.image, boxes[nearest], labels[nearest])
                stickyNames.vote(ids[nearest], name)
            }
        }
        return hazards.mapIndexed { i, hazard ->
            val name = stickyNames.nameOf(ids[i]) ?: return@mapIndexed hazard
            hazard.copy(label = name)
        }
    }

    private fun lookUpName(image: Bitmap, box: FloatArray, label: String): String? = try {
        if (label == PERSON) {
            val faces = faceRecognizer?.recognize(image).orEmpty()
            faces.firstOrNull { it.centerX in box[0]..box[2] && it.centerY in box[1]..box[3] }?.match?.name
        } else {
            val crop = image.cropBox(box[0], box[1], box[2], box[3])
            crop?.let { itemRecognizer?.match(it, null)?.name }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Walk naming failed", e)
        null
    }

    /** Reads the middle of the frame now and then; the same sign is not read twice. */
    private fun readSign(image: Bitmap): String? {
        val reader = signReader ?: return null
        val now = SystemClock.uptimeMillis()
        if (now - lastSignAt < SIGN_GAP_MS) return null
        lastSignAt = now
        val crop = image.cropBox(
            image.width * 0.2f, image.height * 0.1f,
            image.width * 0.8f, image.height * 0.7f,
        ) ?: return null
        val text = reader.read(crop) ?: return null
        if (text == lastSign) return null
        lastSign = text
        return text
    }

    // ---- beacon, main thread ----

    private fun askPlaceName() {
        val input = android.widget.EditText(requireContext()).apply { hint = getString(R.string.walk_place_name) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.walk_save_place)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.walk_save_place) { _, _ ->
                savePlace(input.text?.toString()?.trim().orEmpty())
            }
            .show()
    }

    private fun askWhereTo() {
        val names = places.names()
        if (names.isEmpty()) {
            say(getString(R.string.walk_no_places))
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.walk_guide_me)
            .setItems(names.toTypedArray()) { _, which -> guideTo(names[which]) }
            .show()
    }

    fun savePlace(name: String) {
        if (name.isEmpty()) return
        val fix = lastFix
        if (fix == null) {
            askForLocation()
            say(getString(R.string.walk_no_fix))
            return
        }
        places.save(name, fix.latitude, fix.longitude)
        say(getString(R.string.walk_place_saved, name))
    }

    fun guideTo(name: String) {
        val place = places.place(name)
        if (place == null) {
            say(getString(R.string.walk_place_unknown, name))
            return
        }
        beaconName = name
        beaconPlace = place
        lastBeaconAt = 0
        lastBeaconHour = 0
        askForLocation()
        say(getString(R.string.walk_guiding, name))
    }

    /** Says the distance and clock direction to the saved place, when it changed or now and then. */
    private fun speakBeacon() {
        val name = beaconName ?: return
        val target = beaconPlace ?: return
        val fix = lastFix ?: return
        val heading = compass.headingDeg ?: return
        val metres = Beacon.distanceMetres(fix.latitude, fix.longitude, target.first, target.second)
        val bearing = Beacon.bearingDeg(fix.latitude, fix.longitude, target.first, target.second)
        val hour = Beacon.clockHour(bearing, heading)
        val now = SystemClock.uptimeMillis()
        val changed = abs(hour - lastBeaconHour) > 1 && lastBeaconHour != 0
        if (!changed && now - lastBeaconAt < BEACON_REPEAT_MS) return
        lastBeaconAt = now
        lastBeaconHour = hour
        say(Beacon.phrase(name.replaceFirstChar { it.uppercase() }, metres, hour))
    }

    private fun askForLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startLocation()
        } else {
            requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val manager = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lastFix = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, LOCATION_GAP_MS, LOCATION_GAP_M, locationListener,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Location updates failed", e)
        }
    }

    private fun stopLocation() {
        val manager = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            manager.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.w(TAG, "Stopping location failed", e)
        }
    }

    private val locationListener = LocationListener { location -> lastFix = location }

    // ---- voice ----

    override fun onVoiceCommand(command: VoiceCommand): Boolean = when (command) {
        is VoiceCommand.SavePlace -> {
            savePlace(command.name)
            true
        }
        is VoiceCommand.GoTo -> {
            guideTo(command.name)
            true
        }
        VoiceCommand.Repeat -> {
            if (!speech.repeatLast()) say(getString(R.string.walk_started))
            true
        }
        else -> false
    }

    // ---- shared ----

    private fun say(text: String) {
        val b = _binding ?: return
        b.announcement.text = text
        speech.announce(text)
    }

    private fun showProblem(problem: ArProblem) {
        val text = when (problem) {
            ArProblem.NOT_INSTALLED -> getString(R.string.walk_ar_installing)
            ArProblem.NOT_SUPPORTED -> getString(R.string.walk_ar_unsupported)
            ArProblem.FAILED -> getString(R.string.walk_ar_failed)
        }
        binding.status.text = text
        speech.speakNow(text)
    }

    private fun onMain(block: () -> Unit) {
        activity?.runOnUiThread { if (_binding != null) block() }
    }

    private companion object {
        const val TAG = "ClassroomScanner"
        const val MIN_SCORE = 0.7f

        /** A detected thing and a depth reading this close together are the same object. */
        const val SAME_THING_M = 1f
        const val ANALYSIS_GAP_MS = 250L
        const val SIGN_GAP_MS = 2_500L
        const val NAME_EVERY = 4
        const val BEACON_REPEAT_MS = 20_000L
        const val LOCATION_GAP_MS = 2_000L
        const val LOCATION_GAP_M = 2f
        const val START_SPEECH_MS = 800L
        const val BUZZ_M = 1f
        const val CAMERA_HEIGHT_M = 1.4f
        const val BODY_HEIGHT_M = 1.7f
        const val PERSON = "person"
        const val THING = "thing"
        const val TRAFFIC_LIGHT = "traffic light"
    }
}
