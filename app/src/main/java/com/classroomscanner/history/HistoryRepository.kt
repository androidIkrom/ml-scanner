package com.classroomscanner.history

import com.classroomscanner.core.AngleMath
import com.classroomscanner.core.ScanResult
import kotlinx.coroutines.flow.Flow

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
}
