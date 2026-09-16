package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.classroomscanner.R
import com.classroomscanner.databinding.FragmentHistoryBinding
import com.classroomscanner.history.AppDatabase
import com.classroomscanner.history.HistoryAdapter
import com.classroomscanner.history.HistoryRepository
import com.classroomscanner.speech.SpeechAnnouncer
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var speech: SpeechAnnouncer

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        speech = SpeechAnnouncer(requireContext())
        val adapter = HistoryAdapter { scan ->
            if (speech.available) {
                speech.speakNow(scan.summaryText)
            } else {
                Toast.makeText(requireContext(), R.string.tts_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        val repository = HistoryRepository(AppDatabase.get(requireContext()).scanDao())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.scans().collect { scans ->
                    adapter.submitList(scans)
                    binding.empty.isVisible = scans.isEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        speech.shutdown()
        _binding = null
        super.onDestroyView()
    }
}
