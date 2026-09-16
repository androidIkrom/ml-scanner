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
import com.classroomscanner.R
import com.classroomscanner.core.EnrollmentGuide
import com.classroomscanner.core.Pose
import com.classroomscanner.databinding.FragmentEnrollBinding
import com.classroomscanner.face.FaceEmbedder
import com.classroomscanner.face.FaceFinder
import com.classroomscanner.face.cropFace
import com.classroomscanner.face.upright
import com.classroomscanner.people.PeopleRepository
import com.classroomscanner.speech.SpeechAnnouncer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Step 2 of adding a person: guides the head through five poses, collects FaceNet embeddings,
 * then saves the person. All face work runs on [executor]; views are touched on the main thread only.
 */
class EnrollFragment : Fragment() {

    private val args: EnrollFragmentArgs by navArgs()
    private var _binding: FragmentEnrollBinding? = null
    private val binding get() = _binding!!

    private lateinit var executor: ExecutorService
    private lateinit var speech: SpeechAnnouncer

    // Executor thread only.
    private var finder: FaceFinder? = null
    private var embedder: FaceEmbedder? = null
    private val guide = EnrollmentGuide()
    private val vectors = mutableListOf<FloatArray>()
    private var photo: Bitmap? = null
    private var lastSampleAt = 0L
    private var lastWarningAt = 0L
    private var finished = false

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
                finder = FaceFinder(accurate = true)
                embedder = FaceEmbedder(context)
            } catch (e: Exception) {
                Log.e(TAG, "Face models failed to load", e)
                finished = true
                onMain { showStatus(getString(R.string.enroll_failed), speakIt = true) }
            }
        }
        showProgress(0)
        binding.instruction.text = Pose.STRAIGHT.instruction
        // Give the speech engine a moment to start before the first instruction.
        view.postDelayed({ if (_binding != null) speech.speakNow(Pose.STRAIGHT.instruction) }, FIRST_SPEECH_DELAY_MS)
        binding.viewFinder.post { setUpCamera() }
    }

    override fun onDestroyView() {
        speech.shutdownWhenIdle()
        _binding = null
        super.onDestroyView()
        executor.execute {
            finder?.close()
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
        val finder = finder ?: return
        val embedder = embedder ?: return
        try {
            val upright = frame.upright(rotation)
            val faces = finder.find(upright)
            when {
                faces.isEmpty() -> warn(R.string.enroll_no_face)
                faces.size > 1 -> warn(R.string.enroll_one_face)
                else -> {
                    val face = faces[0]
                    val crop = upright.cropFace(face.boundingBox) ?: return warn(R.string.enroll_no_face)
                    val pose = guide.currentPose
                    if (!guide.offer(face.headEulerAngleY, face.headEulerAngleX)) {
                        onMain { showStatus(null) }
                        return
                    }
                    vectors += embedder.embed(crop)
                    if (pose == Pose.STRAIGHT && photo == null) photo = crop
                    lastSampleAt = SystemClock.uptimeMillis()
                    onSample()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Face sample failed", e)
        }
    }

    // Executor thread.
    private fun onSample() {
        val percent = guide.percent()
        val next = guide.currentPose
        val poseFinished = guide.poseFinished
        val done = guide.done
        if (done) finished = true
        onMain {
            showStatus(null)
            showProgress(percent)
            when {
                done -> save()
                poseFinished && next != null -> {
                    binding.instruction.text = next.instruction
                    speech.speakNow(getString(R.string.enroll_percent, percent) + " " + next.instruction)
                }
            }
        }
    }

    // Executor thread.
    private fun warn(messageRes: Int) {
        val now = SystemClock.uptimeMillis()
        val speakIt = now - lastWarningAt > WARNING_REPEAT_MS
        if (speakIt) lastWarningAt = now
        onMain { showStatus(getString(messageRes), speakIt) }
    }

    private fun save() {
        val face = photo ?: return
        val collected = vectors.toList()
        val name = args.name
        binding.instruction.text = getString(R.string.enroll_saving)
        val repository = PeopleRepository(requireContext())
        lifecycleScope.launch {
            withContext(NonCancellable) { repository.add(name, face, collected) }
            val done = getString(R.string.enroll_done, name)
            speech.speakNow(done)
            _binding?.instruction?.text = done
            findNavController().popBackStack(R.id.people_fragment, false)
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
        const val SAMPLE_GAP_MS = 350L
        const val WARNING_REPEAT_MS = 3_000L
        const val FIRST_SPEECH_DELAY_MS = 1_000L
    }
}
