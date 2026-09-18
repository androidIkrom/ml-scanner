package com.classroomscanner.fragments

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.classroomscanner.MainActivity
import com.classroomscanner.R
import com.classroomscanner.core.VoiceCommand
import com.classroomscanner.guide.VoiceCommandTarget
import com.classroomscanner.databinding.FragmentAddPersonBinding
import com.classroomscanner.databinding.FragmentPersonBinding
import com.classroomscanner.people.PeopleRepository
import com.classroomscanner.people.PhotoLoader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Navigates only while [fromId] is still the current screen (ignores double taps). */
internal fun Fragment.goFrom(fromId: Int, directions: NavDirections) {
    val nav = findNavController()
    if (nav.currentDestination?.id == fromId) nav.navigate(directions)
}

/** Speaks through the app's voice guide even when it is switched off (for errors and results). */
internal fun Fragment.speak(text: String) {
    (activity as? MainActivity)?.voiceGuide?.say(text)
}

/** One saved person: photo, name and Delete. */
class PersonFragment : Fragment(), VoiceCommandTarget {

    private val args: PersonFragmentArgs by navArgs()
    private var _binding: FragmentPersonBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPersonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repository = PeopleRepository(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val person = repository.person(args.personId)
            if (person == null) {
                findNavController().popBackStack()
                return@launch
            }
            requireActivity().findViewById<Toolbar>(R.id.toolbar)?.title = person.name
            binding.name.text = person.name
            binding.photo.contentDescription = getString(R.string.photo_of, person.name)
            binding.photo.setImageBitmap(withContext(Dispatchers.IO) { PhotoLoader.load(person.photoPath, PHOTO_PX) })
            binding.deleteButton.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.delete_person_title, person.name))
                    .setMessage(R.string.delete_person_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(NonCancellable + Dispatchers.IO) { repository.delete(person) }
                            findNavController().popBackStack()
                        }
                    }
                    .show()
            }
        }
    }

    /** "Delete this" works without finding the button. */
    override fun onVoiceCommand(command: VoiceCommand): Boolean = when (command) {
        VoiceCommand.Delete -> {
            _binding?.deleteButton?.performClick()
            true
        }
        else -> false
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val PHOTO_PX = 720
    }
}

/** Step 1 of adding a person: name and camera. */
class AddPersonFragment : Fragment(), VoiceCommandTarget {

    private var _binding: FragmentAddPersonBinding? = null
    private val binding get() = _binding!!

    private val voice = VoiceInputController(this) { text ->
        val name = text.replaceFirstChar { it.uppercase() }
        _binding?.nameInput?.setText(name)
        _binding?.nameInput?.setSelection(name.length)
        speak(getString(R.string.name_heard, name))
    }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startFaceScan() else speakError(getString(R.string.camera_permission_needed))
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddPersonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.sayNameButton.setOnClickListener { voice.listen() }
        voice.onListening = { listening ->
            _binding?.sayNameButton?.setText(if (listening) R.string.listening else R.string.say_name)
        }
        // Blind users can say the name right away.
        if (savedInstanceState == null && voice.available) {
            view.postDelayed({ if (_binding != null) voice.listen() }, AUTO_LISTEN_DELAY_MS)
        }
        binding.startButton.setOnClickListener {
            if (name().isEmpty()) {
                speakError(getString(R.string.person_name_error))
                binding.nameLayout.error = getString(R.string.person_name_error)
                return@setOnClickListener
            }
            binding.nameLayout.error = null
            if (PermissionsFragment.hasPermissions(requireContext())) {
                startFaceScan()
            } else {
                requestCamera.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onDestroyView() {
        voice.release()
        _binding = null
        super.onDestroyView()
    }

    override fun onVoiceCommand(command: VoiceCommand): Boolean = when (command) {
        VoiceCommand.Start -> {
            _binding?.startButton?.performClick()
            true
        }
        else -> false
    }

    private fun name() = binding.nameInput.text?.toString()?.trim().orEmpty()

    private fun startFaceScan() {
        val b = _binding ?: return
        val front = b.cameraGroup.checkedButtonId == R.id.camera_front
        goFrom(R.id.add_person_fragment, AddPersonFragmentDirections.actionAddPersonToEnroll(name(), front))
    }

    private fun speakError(text: String) {
        speak(text)
    }
}
