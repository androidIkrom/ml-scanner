package com.classroomscanner.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertsScanWithObjectsAndReadsBackNewestFirst() = runBlocking {
        val dao = db.scanDao()
        val olderId = dao.insertScanWithObjects(
            ScanEntity(startedAt = 1L, mode = "LIVE", coveragePercent = 40, summaryText = "old"),
            emptyList(),
        )
        val newerId = dao.insertScanWithObjects(
            ScanEntity(startedAt = 2L, mode = "FULL", coveragePercent = 100, summaryText = "Around you: a blue chair in front."),
            listOf(DetectedObjectEntity(scanId = 0, label = "chair", count = 1, colorName = "blue", relAngleDeg = 0f, sector8 = "FRONT")),
        )

        val scans = dao.observeScans().first()
        assertEquals(listOf(newerId, olderId), scans.map { it.id })

        val objects = dao.objectsFor(newerId)
        assertEquals(1, objects.size)
        assertEquals("chair", objects[0].label)
        assertEquals(newerId, objects[0].scanId)
    }
}
