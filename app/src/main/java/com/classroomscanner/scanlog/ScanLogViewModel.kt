package com.classroomscanner.scanlog

import androidx.lifecycle.ViewModel
import com.classroomscanner.core.ScanLogState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Activity-scoped log of the current scan, shared by the scanner and the View text screen. */
class ScanLogViewModel : ViewModel() {
    private val _state = MutableStateFlow(ScanLogState())
    val state: StateFlow<ScanLogState> = _state.asStateFlow()

    fun start() {
        _state.value = ScanLogState()
    }

    fun add(text: String) {
        _state.update { it.add(System.currentTimeMillis(), text) }
    }

    fun finish(summaryText: String) {
        _state.update { it.finish(summaryText) }
    }
}
