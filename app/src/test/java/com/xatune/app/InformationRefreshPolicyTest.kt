package com.xatune.app

import com.xatune.app.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class InformationRefreshPolicyTest {
    private fun day(value: String) = LocalDate.parse(value)
    private fun now(value: String) = ZonedDateTime.parse(value)
    private val terms = listOf(RefreshTerm(day("2026-09-02"), day("2027-01-20")), RefreshTerm(day("2027-02-22"), day("2027-07-10")), RefreshTerm(day("2027-08-31")))
    @Test fun gradesStartWeek12MondayAndStopAtEachNextStart() {
        for ((date, enabled) in listOf("2026-11-15" to false, "2026-11-16" to true, "2027-01-21" to true, "2027-02-21" to true, "2027-02-22" to false, "2027-05-09" to false, "2027-05-10" to true, "2027-08-30" to true, "2027-08-31" to false)) assertEquals(date, enabled, InformationRefreshPolicy.gradesEnabled(terms, day(date)))
    }
    @Test fun vacationStartsDayAfterEndAndStopsAtOpening() {
        for ((date, vacation) in listOf("2027-01-20" to false, "2027-01-21" to true, "2027-02-21" to true, "2027-02-22" to false, "2027-07-10" to false, "2027-07-11" to true, "2027-08-31" to false)) assertEquals(date, vacation, InformationRefreshPolicy.vacation(terms, day(date)))
        assertFalse(InformationRefreshPolicy.vacation(listOf(RefreshTerm(day("2026-09-01"))), day("2027-01-30")))
    }
    @Test fun dailyUsesDeviceZoneAndMidnightRatherThan24Hours() {
        val before = now("2026-11-13T23:59:00+08:00").toInstant().toEpochMilli()
        assertFalse(InformationRefreshPolicy.needsDailyRefresh(before, now("2026-11-13T23:59:59+08:00")))
        assertTrue(InformationRefreshPolicy.needsDailyRefresh(before, now("2026-11-14T00:00:00+08:00")))
        assertFalse(InformationRefreshPolicy.needsDailyRefresh(before, now("2026-11-13T08:00:00-08:00")))
    }
    @Test fun oldCalendarRetriesPastOpeningAndNewYearStops() {
        val starts = listOf(day("2026-09-01"), day("2027-02-20"))
        assertNull(InformationRefreshPolicy.calendarTarget(starts, null, day("2027-07-31")))
        assertEquals(2027, InformationRefreshPolicy.calendarTarget(starts, null, day("2027-08-01")))
        assertEquals(2027, InformationRefreshPolicy.calendarTarget(starts, 2027, day("2027-09-15")))
        assertNull(InformationRefreshPolicy.calendarTarget(listOf(day("2027-09-01"), day("2028-02-20")), 2027, day("2027-09-15")))
        assertEquals(2027, InformationRefreshPolicy.calendarTarget(emptyList(), 2027, day("2027-09-15")))
        assertEquals(0, InformationRefreshPolicy.calendarTarget(emptyList(), null, day("2027-09-15")))
        assertEquals(2026, InformationRefreshPolicy.calendarTarget(listOf(day("2025-09-01"), day("2026-02-20"), day("2026-09-10")), null, day("2026-08-11")))
    }
    @Test fun leapYearWindowIs364DaysAndReducedKeepsExactOldLifetimes() {
        val starts = listOf(day("2023-09-01"), day("2024-02-20"))
        assertNull(InformationRefreshPolicy.calendarTarget(starts, null, day("2024-07-30")))
        assertEquals(2024, InformationRefreshPolicy.calendarTarget(starts, null, day("2024-07-31")))
        val current = now("2026-11-20T12:00:00+08:00")
        for (kind in InformationKind.entries) {
            val boundary = current.toInstant().toEpochMilli() - kind.hours * 3_600_000
            assertTrue(InformationRefreshPolicy.stale(kind, boundary, true, terms, current))
            assertFalse(InformationRefreshPolicy.stale(kind, boundary + 1, true, terms, current))
        }
        assertFalse(InformationRefreshPolicy.stale(InformationKind.PLAN, null, false, terms, current))
        assertTrue(InformationRefreshPolicy.stale(InformationKind.GRADES, null, false, terms, current))
        assertFalse(InformationRefreshPolicy.stale(InformationKind.EXAMS, null, false, terms, now("2027-01-22T12:00:00+08:00")))
    }
    @Test fun latestCorrectedCalendarWinsAndMetadataCannotOverwriteIt() {
        val old = SchoolCalendar("", "", "old", "", "", listOf(SemesterSpan("旧", "2026-09-01", "2027-01-20")), 0)
        val corrected = old.copy(imageUrl = "corrected", semesters = listOf(SemesterSpan("新", "2026-09-02", "2027-01-22")))
        val resolved = InformationRefreshPolicy.terms(listOf(old, corrected), listOf(Semester("1", "", "2026-09-01")), "2020-01-01")
        assertEquals(RefreshTerm(day("2026-09-02"), day("2027-01-22")), resolved.first())
        assertTrue(resolved.any { it.start == day("2027-09-01") })
        assertEquals(day("2026-09-07"), InformationRefreshPolicy.terms(emptyList(), emptyList(), "2026-09-07").single().start)
    }
    private val grade = Grade("unstable", "数学", "M01", "3", "88", "3.8", "2026-1", listOf(GradePart("平时", "90"), GradePart("期末", "87")))
    @Test fun fingerprintsIgnoreOrderIdsAndPartOrderButDetectAddChangeAndDelete() {
        val second = grade.copy(course = "程序设计", code = "CS01")
        val original = gradeFingerprint(listOf(grade, second))
        assertEquals(original, gradeFingerprint(listOf(second.copy(id = "different"), grade.copy(parts = grade.parts.reversed()))))
        assertNotEquals(original, gradeFingerprint(listOf(grade)))
        assertNotEquals(original, gradeFingerprint(listOf(grade, second.copy(score = "90"))))
        assertNotEquals(original, gradeFingerprint(listOf(grade, second, second.copy(course = "物理"))))
        assertNotEquals(gradeFingerprint(listOf(grade)), gradeFingerprint(emptyList()))
    }
}
