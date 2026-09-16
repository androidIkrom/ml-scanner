package com.classroomscanner.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val mode: String,
    val coveragePercent: Int,
    val summaryText: String,
)

@Entity(
    tableName = "detected_objects",
    foreignKeys = [
        ForeignKey(
            entity = ScanEntity::class,
            parentColumns = ["id"],
            childColumns = ["scanId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("scanId")],
)
data class DetectedObjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scanId: Long,
    val label: String,
    val count: Int,
    val colorName: String?,
    val relAngleDeg: Float,
    val sector8: String,
)
