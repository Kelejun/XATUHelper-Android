package com.xatune.app

import com.xatune.app.model.*
import org.junit.Assert.*
import org.junit.Test

class CalendarCorrectionTest {
    private val first = SemesterSpan(" 第一学期 ", "2026-09-01", "2027-01-20")
    private val second = SemesterSpan("第二学期", "2027-02-20", "2027-07-10")
    @Test fun normalizesCrossYearOrderAndNotes() {
        val value = CalendarCorrection(listOf(second, first), listOf(" 备注 ", " ")).normalized()
        assertNull(value.error()); assertEquals("第一学期", value.semesters.first().label)
        assertEquals(listOf("备注"), value.notes)
    }
    @Test fun rejectsEmptyInvalidInvertedAndOverlappingDates() {
        assertNotNull(CalendarCorrection(emptyList()).error())
        for (span in listOf(first.copy(label = " "), first.copy(start = "2026-02-30"), first.copy(end = "2026-01-01"), first.copy(start = "2026-9-01"))) assertNotNull(CalendarCorrection(listOf(span)).error())
        assertNotNull(CalendarCorrection(listOf(first, second.copy(start = first.end))).error())
        assertNull(CalendarCorrection(listOf(first, second.copy(start = "2027-01-21"))).error())
    }
}
