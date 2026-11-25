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
    @Test fun examWidgetSnapshotDisplaysCountdownRoomSeatUpcomingAndSchedulesTransitions() {
        val anchor = LocalDate.parse("2026-11-02")
        val exams = listOf(
            Exam("e1", "高等数学", "M1", "", "2026-11-16 09:00–11:00", "教1-101", "12", ""),
            Exam("e2", "线性代数", "M2", "", "2026-11-16 14:30", "教2-205", "", ""),
            Exam("e3", "大学物理", "P1", "", "2026-11-18 08:30–10:30", "", "8", ""),
            Exam("e4", "概率论", "M3", "", "2026-11-20", "教3-301", "21", ""),
            Exam("e5", "待定考试", "X1", "", "时间待定", "教1-102", "", "")
        )
        val snapshot = widgetExamSnapshot("acc", "sem1", 1000L, exams, anchor)
        assertEquals(4, snapshot.exams.size)

        // Before exam day: shows days remaining, room, seat; wide widget lists up to 2 upcoming exams.
        val before = ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, SchoolZone)
        val narrowBefore = formatExamWidget(snapshot, before, wide = false)
        assertEquals("考试倒计时 · 还有 2 天", narrowBefore.title)
        assertEquals("高等数学\n2026-11-16 09:00", narrowBefore.body)
        assertEquals("教1-101 · 座位 12", narrowBefore.subtitle)

        val wideBefore = formatExamWidget(snapshot, before, wide = true)
        assertTrue(wideBefore.body.contains("后续 · 线性代数 · 2026-11-16 14:30"))
        assertTrue(wideBefore.body.contains("后续 · 大学物理 · 2026-11-18 08:30"))
        assertFalse(wideBefore.body.contains("概率论"))

        // On exam day before end: shows "今天" and schedules refresh at exact exam end time.
        val examMorning = ZonedDateTime.of(2026, 11, 16, 10, 0, 0, 0, SchoolZone)
        val duringFirst = formatExamWidget(snapshot, examMorning, wide = false)
        assertEquals("考试倒计时 · 今天", duringFirst.title)
        assertEquals(
            ZonedDateTime.of(2026, 11, 16, 11, 0, 0, 0, SchoolZone).toInstant().toEpochMilli(),
            nextWidgetRefresh(WidgetCourseSnapshot("acc", "", emptyList()), examMorning, snapshot)
        )

        // After first exam ends at 11:00: advances to second exam (unknown end time stays until end of day).
        val afterFirst = ZonedDateTime.of(2026, 11, 16, 11, 0, 0, 0, SchoolZone)
        val secondDisplay = formatExamWidget(snapshot, afterFirst, wide = false)
        assertEquals("考试倒计时 · 今天", secondDisplay.title)
        assertEquals("线性代数\n2026-11-16 14:30", secondDisplay.body)
        assertEquals("教2-205", secondDisplay.subtitle)
        assertEquals(
            ZonedDateTime.of(2026, 11, 17, 0, 0, 0, 0, SchoolZone).toInstant().toEpochMilli(),
            nextWidgetRefresh(WidgetCourseSnapshot("acc", "", emptyList()), afterFirst, snapshot)
        )

        // Legacy/missing room falls back to "考场待定".
        val nextDay = ZonedDateTime.of(2026, 11, 17, 8, 0, 0, 0, SchoolZone)
        val thirdDisplay = formatExamWidget(snapshot, nextDay, wide = false)
        assertEquals("考试倒计时 · 还有 1 天", thirdDisplay.title)
        assertEquals("考场待定 · 座位 8", thirdDisplay.subtitle)

        // Empty state after all exams finish, and login prompt when signed out.
        val finished = formatExamWidget(snapshot, ZonedDateTime.of(2026, 11, 21, 0, 0, 0, 0, SchoolZone), wide = false)
        assertEquals("暂无待考安排", finished.body)
        val loggedOut = formatExamWidget(widgetExamSnapshot("", "", 0L, exams, anchor), examMorning, wide = false)
        assertEquals("登录后查看考试安排", loggedOut.body)
    }
}
