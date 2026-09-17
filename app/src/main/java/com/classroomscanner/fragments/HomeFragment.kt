package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.classroomscanner.R
import com.classroomscanner.core.ScanMode
import com.classroomscanner.core.VoiceCommand
import com.classroomscanner.core.VoiceCommandParser
import com.classroomscanner.databinding.FragmentHomeBinding
import com.classroomscanner.sensor.HeadingProvider

/** Start screen: Full scan hub, Search and Saved, plus spoken commands. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val voice = VoiceInputController(this) { onCommand(it) }

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

        binding.voiceButton.setOnClickListener { voice.listen() }
        voice.onListening = { listening ->
            _binding?.voiceButton?.setText(if (listening) R.string.listening else R.string.voice_command)
        }
    }

    override fun onDestroyView() {
        voice.release()
        _binding = null
        super.onDestroyView()
    }

    private fun onCommand(text: String) {
        val here = R.id.home_fragment
        when (val command = VoiceCommandParser.parse(text)) {
            VoiceCommand.FullScan ->
                if (HeadingProvider.isAvailable(requireContext())) {
                    goFrom(here, HomeFragmentDirections.actionHomeToSettings(ScanMode.FULL))
                } else {
                    speak(getString(R.string.home_no_sensor))
                }
            VoiceCommand.LiveScan -> goFrom(here, HomeFragmentDirections.actionHomeToSettings(ScanMode.LIVE))
            VoiceCommand.History -> goFrom(here, HomeFragmentDirections.actionHomeToHistory())
            VoiceCommand.Saved -> goFrom(here, HomeFragmentDirections.actionHomeToSaved())
            is VoiceCommand.Search -> goFrom(here, HomeFragmentDirections.actionHomeToSearch().setQuery(command.query))
            VoiceCommand.Unknown -> speak(getString(R.string.unknown_command))
        }
    }
}
