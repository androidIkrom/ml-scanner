package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.classroomscanner.R
import com.classroomscanner.core.CameraFacing
import com.classroomscanner.core.Compute
import com.classroomscanner.core.ModelChoice
import com.classroomscanner.core.VoiceCommand
import com.classroomscanner.guide.VoiceCommandTarget
import com.classroomscanner.core.ScanMode
import com.classroomscanner.core.ScanSettings
import com.classroomscanner.databinding.FragmentSettingsBinding
import com.classroomscanner.settings.SettingsStore
import java.util.Locale

/** Lets the user choose camera, processing, model, confidence, speech and colors before scanning. */
class SettingsFragment : Fragment(), VoiceCommandTarget {

    private val args: SettingsFragmentArgs by navArgs()
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val store = SettingsStore(requireContext())
        val full = args.mode == ScanMode.FULL
        binding.modeTitle.setText(if (full) R.string.mode_full else R.string.mode_live)
        binding.modeDesc.setText(if (full) R.string.home_full_desc else R.string.home_live_desc)
        binding.modeIcon.setImageResource(if (full) R.drawable.ic_360_24 else R.drawable.ic_graphic_eq_24)

        showOptions(savedInstanceState?.getBoolean(KEY_OPTIONS_OPEN) ?: false)
        binding.optionsHeader.setOnClickListener { showOptions(!binding.optionsContent.isVisible) }

        if (savedInstanceState == null) show(store.load())
        showConfidence(binding.confidenceSlider.value)
        binding.confidenceSlider.addOnChangeListener { _, value, _ -> showConfidence(value) }

        binding.startButton.setOnClickListener {
            store.save(readSettings())
            val nav = findNavController()
            if (nav.currentDestination?.id != R.id.settings_fragment) return@setOnClickListener
            if (PermissionsFragment.hasPermissions(requireContext())) {
                nav.navigate(SettingsFragmentDirections.actionSettingsToCamera(args.mode))
            } else {
                nav.navigate(SettingsFragmentDirections.actionSettingsToPermissions(args.mode))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { outState.putBoolean(KEY_OPTIONS_OPEN, it.optionsContent.isVisible) }
    }

    /** Spoken commands for this screen. */
    override fun onVoiceCommand(command: VoiceCommand): Boolean = when (command) {
        VoiceCommand.Start -> {
            _binding?.startButton?.performClick()
            true
        }
        else -> false
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** The settings cards stay hidden until the Options row is tapped. */
    private fun showOptions(open: Boolean) {
        binding.optionsContent.isVisible = open
        binding.optionsChevron.rotation = if (open) 180f else 0f
        binding.optionsHeader.contentDescription =
            getString(if (open) R.string.options_expanded else R.string.options_collapsed)
    }

    private fun show(s: ScanSettings) {
        binding.cameraGroup.check(if (s.camera == CameraFacing.FRONT) R.id.camera_front else R.id.camera_back)
        binding.computeGroup.check(if (s.compute == Compute.GPU) R.id.compute_gpu else R.id.compute_cpu)
        binding.modelGroup.check(
            when (s.model) {
                ModelChoice.LIGHT -> R.id.model_light
                ModelChoice.FAST -> R.id.model_fast
                ModelChoice.ACCURATE -> R.id.model_accurate
            }
        )
        binding.confidenceSlider.value = s.minScore
        binding.speechSwitch.isChecked = s.speechOn
        binding.colorsSwitch.isChecked = s.colorsOn
    }

    private fun readSettings() = ScanSettings(
        camera = if (binding.cameraGroup.checkedButtonId == R.id.camera_front) CameraFacing.FRONT else CameraFacing.BACK,
        compute = if (binding.computeGroup.checkedButtonId == R.id.compute_gpu) Compute.GPU else Compute.CPU,
        model = when (binding.modelGroup.checkedButtonId) {
            R.id.model_light -> ModelChoice.LIGHT
            R.id.model_fast -> ModelChoice.FAST
            else -> ModelChoice.ACCURATE
        },
        minScore = binding.confidenceSlider.value,
        speechOn = binding.speechSwitch.isChecked,
        colorsOn = binding.colorsSwitch.isChecked,
    )

    private fun showConfidence(value: Float) {
        binding.confidenceValue.text = String.format(Locale.US, "%.1f", value)
    }

    private companion object {
        const val KEY_OPTIONS_OPEN = "options_open"
    }
}
