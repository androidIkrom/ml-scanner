package com.classroomscanner.fragments

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.classroomscanner.R
import com.classroomscanner.core.ItemKind
import com.classroomscanner.core.SavedKind
import com.classroomscanner.core.SavedName
import com.classroomscanner.core.SearchResolver
import com.classroomscanner.core.SearchTarget
import com.classroomscanner.core.VoiceCommand
import com.classroomscanner.guide.VoiceCommandTarget
import com.classroomscanner.databinding.FragmentSearchBinding
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.people.PeopleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asks what to find (voice or text), resolves it, then opens the search camera. */
class SearchFragment : Fragment(), VoiceCommandTarget {

    private val args: SearchFragmentArgs by navArgs()
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val voice = VoiceInputController(this) { resolve(it) }
    private var pendingTarget: SearchTarget? = null
    private var queryUsed = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val target = pendingTarget
            if (granted && target != null) open(target) else speak(getString(R.string.camera_permission_needed))
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.micButton.isEnabled = voice.available
        binding.micButton.setOnClickListener { voice.listen() }
        voice.onListening = { listening ->
            _binding?.micButton?.setText(if (listening) R.string.listening else R.string.search_speak)
        }
        binding.searchButton.setOnClickListener { resolve(binding.queryInput.text?.toString().orEmpty()) }
        binding.queryInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                resolve(v.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        val query = args.query
        if (query != null && !queryUsed && savedInstanceState == null) {
            queryUsed = true
            resolve(query)
        } else {
            speak(getString(R.string.search_prompt))
        }
    }

    override fun onDestroyView() {
        voice.release()
        _binding = null
        super.onDestroyView()
    }

    override fun onVoiceCommand(command: VoiceCommand): Boolean = when (command) {
        VoiceCommand.Start -> {
            // With something typed, search it; with nothing, listen for it.
            val typed = _binding?.queryInput?.text?.toString().orEmpty()
            if (typed.isBlank()) voice.listen() else resolve(typed)
            true
        }
        else -> false
    }

    private fun resolve(text: String) {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val people = PeopleRepository(context).people().first()
                    .map { SavedName(SavedKind.PERSON, it.id, it.name, null) }
                val items = ItemRepository(context).allItems().map {
                    val kind = if (it.kind == ItemKind.CAR.name) SavedKind.CAR else SavedKind.OBJECT
                    SavedName(kind, it.id, it.name, it.label)
                }
                people + items
            }
            val b = _binding ?: return@launch
            b.queryInput.setText(text)
            when (val target = SearchResolver.resolve(text, saved)) {
                is SearchTarget.Unknown -> {
                    val message = if (target.text.isEmpty()) {
                        getString(R.string.search_nothing)
                    } else {
                        getString(R.string.search_unknown, target.text)
                    }
                    b.status.text = message
                    speak(message)
                }
                else -> {
                    pendingTarget = target
                    if (PermissionsFragment.hasPermissions(requireContext())) {
                        open(target)
                    } else {
                        requestCamera.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        }
    }

    private fun open(target: SearchTarget) {
        val directions = when (target) {
            is SearchTarget.Person ->
                SearchFragmentDirections.actionSearchToSearchCamera(KIND_PERSON, target.id, target.name, "person", false)
            is SearchTarget.Item ->
                SearchFragmentDirections.actionSearchToSearchCamera(KIND_ITEM, target.id, target.name, target.label, false)
            is SearchTarget.Label ->
                SearchFragmentDirections.actionSearchToSearchCamera(KIND_LABEL, 0L, target.label, target.label, false)
            is SearchTarget.Unknown -> return
        }
        goFrom(R.id.search_fragment, directions)
    }

    companion object {
        const val KIND_PERSON = "PERSON"
        const val KIND_ITEM = "ITEM"
        const val KIND_LABEL = "LABEL"
    }
}
