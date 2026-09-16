package com.classroomscanner.history

import android.util.Log
import com.classroomscanner.core.AngleMath
import com.classroomscanner.core.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class HistoryRepository(private val dao: ScanDao) {

    fun scans(): Flow<List<ScanEntity>> = dao.observeScans()

    suspend fun save(result: ScanResult): Long = dao.insertScanWithObjects(
        ScanEntity(
            startedAt = result.startedAt,
            mode = result.mode.name,
            coveragePercent = result.coveragePercent,
            summaryText = result.summaryText,
        ),
        result.objects.map {
            DetectedObjectEntity(
                scanId = 0,
                label = it.label,
                count = it.count,
                colorName = it.color,
                relAngleDeg = it.angle,
                sector8 = AngleMath.sector8(it.angle).name,
            )
        },
    )

    /**
     * Saves on a process-lifetime scope so the write survives the caller's scope being
     * cancelled (e.g. a fragment tearing down), and never throws.
     */
    fun saveDetached(result: ScanResult) {
        processScope.launch {
            try {
                save(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save scan to history", e)
            }
        }
    }

    private companion object {
        const val TAG = "ClassroomScanner"
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
