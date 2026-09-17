package com.classroomscanner.fragments

import android.Manifest
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.classroomscanner.R
import com.classroomscanner.core.ItemKind
import com.classroomscanner.databinding.FragmentAddPersonBinding
import com.classroomscanner.databinding.FragmentPersonBinding
import com.classroomscanner.databinding.FragmentSavedBinding
import com.classroomscanner.items.ItemRepository
import com.classroomscanner.people.PeopleRepository
import com.classroomscanner.people.PhotoLoader
import com.classroomscanner.people.SavedAdapter
import com.classroomscanner.people.SavedRow
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saved people, cars and objects in three tabs; the big button adds to the open tab. */
class SavedFragment : Fragment() {

    private var _binding: FragmentSavedBinding? = null
    private val binding get() = _binding!!

    /** Kept on the fragment so the tab survives going to a detail screen and back. */
    private var selectedTab = TAB_PEOPLE
    private var listJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSavedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.let { selectedTab = it.getInt(KEY_TAB, selectedTab) }
        val here = R.id.saved_fragment
        val adapter = SavedAdapter { row ->
            if (selectedTab == TAB_PEOPLE) {
                goFrom(here, SavedFragmentDirections.actionSavedToPerson(row.id))
            } else {
                goFrom(here, SavedFragmentDirections.actionSavedToItem(row.id))
            }
        }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        listOf(R.string.tab_people, R.string.tab_cars, R.string.tab_objects).forEach {
            binding.tabs.addTab(binding.tabs.newTab().setText(it))
        }
        binding.tabs.getTabAt(selectedTab)?.select()
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                show(adapter)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.addButton.setOnClickListener {
            when (selectedTab) {
                TAB_PEOPLE -> goFrom(here, SavedFragmentDirections.actionSavedToAddPerson())
                TAB_CARS -> goFrom(here, SavedFragmentDirections.actionSavedToAddItem(ItemKind.CAR.name))
                else -> goFrom(here, SavedFragmentDirections.actionSavedToAddItem(ItemKind.OBJECT.name))
            }
        }
        show(adapter)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, selectedTab)
    }

    override fun onDestroyView() {
        listJob = null
        _binding = null
        super.onDestroyView()
    }

    private fun show(adapter: SavedAdapter) {
        val b = _binding ?: return
        val context = requireContext()
        val (addText, addIcon) = when (selectedTab) {
            TAB_PEOPLE -> R.string.add_person to R.drawable.ic_person_add_24
            TAB_CARS -> R.string.add_car to R.drawable.ic_car_24
            else -> R.string.add_object to R.drawable.ic_category_24
        }
        b.addButton.setText(addText)
        b.addButton.setIconResource(addIcon)
        val (emptyTitle, emptyHint, emptyIcon) = when (selectedTab) {
            TAB_PEOPLE -> Triple(R.string.people_empty, R.string.people_empty_hint, R.drawable.ic_face_24)
            TAB_CARS -> Triple(R.string.cars_empty, R.string.cars_empty_hint, R.drawable.ic_car_24)
            else -> Triple(R.string.objects_empty, R.string.objects_empty_hint, R.drawable.ic_category_24)
        }
        b.emptyTitle.setText(emptyTitle)
        b.emptyHint.setText(emptyHint)
        b.emptyIcon.setImageResource(emptyIcon)

        val rows = when (selectedTab) {
            TAB_PEOPLE -> PeopleRepository(context).people().map { list ->
                list.map { SavedRow(it.id, it.name, it.photoPath) }
            }
            TAB_CARS -> ItemRepository(context).items(ItemKind.CAR).map { list ->
                list.map { SavedRow(it.id, it.name, it.photoPath) }
            }
            else -> ItemRepository(context).items(ItemKind.OBJECT).map { list ->
                list.map { SavedRow(it.id, it.name, it.photoPath) }
            }
        }
        listJob?.cancel()
        adapter.submitList(emptyList())
        listJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                rows.collect { list ->
                    adapter.submitList(list)
                    _binding?.empty?.isVisible = list.isEmpty()
                }
            }
        }
    }

    private companion object {
        const val TAB_PEOPLE = 0
        const val TAB_CARS = 1
        const val KEY_TAB = "saved_tab"
    }
}

/** One saved car or object: photo, name and Delete. */
class ItemFragment : Fragment() {

    private val args: ItemFragmentArgs by navArgs()
    private var _binding: FragmentPersonBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPersonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repository = ItemRepository(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val item = repository.item(args.itemId)
            if (item == null) {
                findNavController().popBackStack()
                return@launch
            }
            requireActivity().findViewById<Toolbar>(R.id.toolbar)?.title = item.name
            binding.name.text = item.name
            binding.photo.contentDescription = getString(R.string.photo_of, item.name)
            binding.photo.setImageBitmap(withContext(Dispatchers.IO) { PhotoLoader.load(item.photoPath, PHOTO_PX) })
            binding.deleteButton.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.delete_person_title, item.name))
                    .setMessage(R.string.delete_item_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(NonCancellable + Dispatchers.IO) { repository.delete(item) }
                            findNavController().popBackStack()
                        }
                    }
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val PHOTO_PX = 720
    }
}

/** Step 1 of adding a car or object: name and camera. */
class AddItemFragment : Fragment() {

    private val args: AddItemFragmentArgs by navArgs()
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
            if (granted) startItemScan() else speak(getString(R.string.camera_permission_needed))
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddPersonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val car = args.kind == ItemKind.CAR.name
        requireActivity().findViewById<Toolbar>(R.id.toolbar)?.title =
            getString(if (car) R.string.add_car else R.string.add_object)
        binding.nameInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        binding.hint.setText(if (car) R.string.add_car_hint else R.string.add_object_hint)
        binding.startButton.setText(if (car) R.string.add_car else R.string.add_object)
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
                speak(getString(R.string.person_name_error))
                binding.nameLayout.error = getString(R.string.person_name_error)
                return@setOnClickListener
            }
            binding.nameLayout.error = null
            if (PermissionsFragment.hasPermissions(requireContext())) {
                startItemScan()
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

    private fun name() = binding.nameInput.text?.toString()?.trim().orEmpty()

    private fun startItemScan() {
        val b = _binding ?: return
        val front = b.cameraGroup.checkedButtonId == R.id.camera_front
        goFrom(R.id.add_item_fragment, AddItemFragmentDirections.actionAddItemToItemEnroll(name(), args.kind, front))
    }
}
