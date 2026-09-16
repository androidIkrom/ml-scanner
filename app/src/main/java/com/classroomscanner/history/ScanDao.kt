package com.classroomscanner.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ScanDao {

    @Insert
    abstract suspend fun insertScan(scan: ScanEntity): Long

    @Insert
    abstract suspend fun insertObjects(objects: List<DetectedObjectEntity>)

    @Transaction
    open suspend fun insertScanWithObjects(scan: ScanEntity, objects: List<DetectedObjectEntity>): Long {
        val id = insertScan(scan)
        insertObjects(objects.map { it.copy(scanId = id) })
        return id
    }

    @Query("SELECT * FROM scans ORDER BY startedAt DESC")
    abstract fun observeScans(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM detected_objects WHERE scanId = :scanId")
    abstract suspend fun objectsFor(scanId: Long): List<DetectedObjectEntity>
}
