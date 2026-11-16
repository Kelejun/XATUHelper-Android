package com.xatune.app

import com.xatune.app.data.*
import com.xatune.app.model.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DataConsistencyTest {
    @Test fun invalidationRejectsOldSuccessAndFailureEvenForSameAccount() {
        val epoch = SessionEpoch(); val old = epoch.capture(); epoch.check(old); epoch.invalidate()
        assertThrows(CancellationException::class.java) { epoch.check(old) }; epoch.check(epoch.capture())
    }
    @Test fun examDateNormalizationPreservesTimeForAllSeparators() {
        for (date in listOf("2026-11-16", "2026/11/16", "2026.11.16", "2026年11月16日")) {
            assertEquals("2026-11-16 09:00–11:00", normalizedExamTime("$date 09:00–11:00"))
        }
    }
    @Test fun noticesIgnoreDisplayIdsAndPartOrderButDetectBusinessChanges() {
        val grade = Grade("row-1", "数学", "M01", "3", "88", "3.8", "2026-1", listOf(GradePart("平时", "90"), GradePart("期末", "87")))
        assertTrue(changedGrades(listOf(grade), listOf(grade.copy(id = "row-8", parts = grade.parts.reversed()))).isEmpty())
        for (changed in listOf(grade.copy(score = "89"), grade.copy(credit = "4"), grade.copy(gpa = "4"))) assertEquals(1, changedGrades(listOf(grade), listOf(changed)).size)
    }
    @Test fun datedWidgetSnapshotAdvancesExpiresAndSchedulesBoundaries() {
        val date = LocalDate.parse("2026-11-16")
        val table = Timetable("a", Semester("current", ""), TableKind.STUDENT, listOf(Course("c", "数学", "", "教1", 1, 1, listOf(1, 2))), 12, date.toString(), 1)
        val snapshot = widgetSnapshot("a", table, date)
        assertEquals(14, snapshot.days.size); assertEquals(1, snapshot.day(date)!!.week)
        assertEquals(2, snapshot.day(date.plusWeeks(1))!!.week); assertTrue(snapshot.day(date.plusDays(1))!!.courses.isEmpty())
        assertNull(snapshot.day(date.plusDays(14))); assertTrue(widgetSnapshot("", table, date).days.isEmpty())
        assertTrue(widgetSnapshot("other", table, date).days.isEmpty()); assertTrue(widgetSnapshot("a", table.copy(kind = TableKind.CLASS), date).days.isEmpty())
        val morning = date.atTime(8, 0).atZone(SchoolZone)
        assertEquals(date.atTime(8, 20).atZone(SchoolZone).toInstant().toEpochMilli(), nextWidgetRefresh(snapshot, morning))
        assertEquals(date.atTime(9, 5).atZone(SchoolZone).toInstant().toEpochMilli(), nextWidgetRefresh(snapshot, morning.plusMinutes(21)))
        assertEquals(date.plusDays(1).atStartOfDay(SchoolZone).toInstant().toEpochMilli(), nextWidgetRefresh(snapshot, morning.plusHours(3)))
    }
}
