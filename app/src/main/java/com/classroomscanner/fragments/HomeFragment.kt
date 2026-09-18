package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.classroomscanner.MainActivity
import com.classroomscanner.R
import com.classroomscanner.databinding.FragmentHomeBinding

/** Start screen: Full scan hub, Search and Saved, plus spoken commands. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val here = R.id.home_fragment
        binding.cardScan.setOnClickListener { goFrom(here, HomeFragmentDirections.actionHomeToScanHub()) }
        binding.cardSearch.setOnClickListener { goFrom(here, HomeFragmentDirections.actionHomeToSearch()) }
        binding.cardSaved.setOnClickListener { goFrom(here, HomeFragmentDirections.actionHomeToSaved()) }

        binding.voiceButton.setOnClickListener { (activity as? MainActivity)?.toggleVoiceCommands() }
        binding.learnerToggle.setOnClickListener {
            (activity as? MainActivity)?.toggleLearnerMode()
            showLearnerState()
        }
        showLearnerState()
    }

    private fun showLearnerState() {
        val on = (activity as? MainActivity)?.learnerOn == true
        binding.learnerToggle.setText(if (on) R.string.learner_state_on else R.string.learner_state_off)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
