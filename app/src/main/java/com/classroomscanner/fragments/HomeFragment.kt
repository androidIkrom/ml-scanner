package com.classroomscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import com.classroomscanner.R
import com.classroomscanner.core.ScanMode
import com.classroomscanner.databinding.FragmentHomeBinding
import com.classroomscanner.sensor.HeadingProvider

/** Start screen: pick Full Scan, Live Scan or History. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val hasSensor = HeadingProvider(requireContext(), NoOpListener).isAvailable
        binding.cardFull.isEnabled = hasSensor
        binding.cardFull.alpha = if (hasSensor) 1f else DISABLED_ALPHA
        if (!hasSensor) binding.fullDesc.setText(R.string.home_no_sensor)

        binding.cardFull.setOnClickListener { go(HomeFragmentDirections.actionHomeToSettings(ScanMode.FULL)) }
        binding.cardLive.setOnClickListener { go(HomeFragmentDirections.actionHomeToSettings(ScanMode.LIVE)) }
        binding.cardHistory.setOnClickListener { go(HomeFragmentDirections.actionHomeToHistory()) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Ignores double taps that would otherwise navigate twice. */
    private fun go(directions: NavDirections) {
        val nav = findNavController()
        if (nav.currentDestination?.id == R.id.home_fragment) nav.navigate(directions)
    }

    private object NoOpListener : HeadingProvider.Listener {
        override fun onHeading(relHeading: Float, speedDegPerSec: Float) = Unit
        override fun onAccuracyLow(low: Boolean) = Unit
    }

    private companion object {
        const val DISABLED_ALPHA = 0.5f
    }
}
