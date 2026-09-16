package com.classroomscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLogStateTest {

    @Test
    fun startsEmpty() {
        val s = ScanLogState()
        assertTrue(s.entries.isEmpty())
        assertNull(s.summary)
    }

    @Test
    fun addAppendsInOrderAndTrims() {
        val s = ScanLogState().add(1L, "Turn slowly.").add(2L, "  Chair in front. ")
        assertEquals(listOf(LogEntry(1L, "Turn slowly."), LogEntry(2L, "Chair in front.")), s.entries)
    }

    @Test
    fun blankTextIsIgnored() {
        val s = ScanLogState().add(1L, "Hi.")
        assertSame(s, s.add(2L, "   "))
    }

    @Test
    fun finishKeepsEntriesAndSetsSummary() {
        val s = ScanLogState().add(1L, "Hi.").finish("Around you: a chair in front.")
        assertEquals(1, s.entries.size)
        assertEquals("Around you: a chair in front.", s.summary)
    }
}
