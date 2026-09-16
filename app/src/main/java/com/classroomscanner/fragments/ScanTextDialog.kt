package com.classroomscanner.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.classroomscanner.MainActivity
import com.classroomscanner.R
import com.classroomscanner.databinding.DialogScanTextBinding
import com.classroomscanner.scanlog.LogAdapter
import com.classroomscanner.scanlog.ScanLogViewModel
import kotlinx.coroutines.launch

/** Full-screen log of the current scan, shown over the scanner so scanning continues underneath. */
class ScanTextDialog : DialogFragment() {

    private val scanLog: ScanLogViewModel by activityViewModels()
    private var _binding: DialogScanTextBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_App_FullScreenDialog)
    }

    /** This window is separate from the activity, so its touches are passed to the voice guide here. */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        object : Dialog(requireContext(), theme) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                window?.let { w -> (activity as? MainActivity)?.voiceGuide?.onTouch(w.decorView, ev) }
                return super.dispatchTouchEvent(ev)
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogScanTextBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { dismiss() }

        val adapter = LogAdapter()
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                scanLog.state.collect { state ->
                    adapter.submitList(state.entries) {
                        if (state.entries.isNotEmpty()) _binding?.list?.scrollToPosition(state.entries.size - 1)
                    }
                    binding.empty.isVisible = state.entries.isEmpty() && state.summary == null
                    binding.summaryCard.isVisible = state.summary != null
                    binding.summaryText.text = state.summary
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "ScanTextDialog"
    }
}
