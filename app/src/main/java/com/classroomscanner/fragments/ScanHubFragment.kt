package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.classroomscanner.R
import com.classroomscanner.core.ScanMode
import com.classroomscanner.databinding.FragmentScanHubBinding
import com.classroomscanner.sensor.HeadingProvider

/** Full scan hub: Full Scan, Live Scan or History. */
class ScanHubFragment : Fragment() {

    private var _binding: FragmentScanHubBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScanHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val hasSensor = HeadingProvider.isAvailable(requireContext())
        binding.cardFull.isEnabled = hasSensor
        binding.cardFull.alpha = if (hasSensor) 1f else DISABLED_ALPHA
        if (!hasSensor) binding.fullDesc.setText(R.string.home_no_sensor)

        val here = R.id.scan_hub_fragment
        binding.cardFull.setOnClickListener {
            goFrom(here, ScanHubFragmentDirections.actionScanHubToSettings(ScanMode.FULL))
        }
        binding.cardLive.setOnClickListener {
            goFrom(here, ScanHubFragmentDirections.actionScanHubToSettings(ScanMode.LIVE))
        }
        binding.cardHistory.setOnClickListener { goFrom(here, ScanHubFragmentDirections.actionScanHubToHistory()) }
        binding.cardWalk.setOnClickListener { goFrom(here, ScanHubFragmentDirections.actionScanHubToWalk()) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val DISABLED_ALPHA = 0.5f
    }
}
