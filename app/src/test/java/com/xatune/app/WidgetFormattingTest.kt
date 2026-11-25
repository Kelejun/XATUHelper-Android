package com.xatune.app

import com.xatune.app.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class WidgetFormattingTest {
    @Test fun courseWidgetFormatsHeaderOngoingUpcomingAndTomorrow() {
        val monday = LocalDate.of(2026, 11, 23)
        val wednesday = monday.plusDays(2) // 周三
        val thursday = monday.plusDays(3) // 周四
        val courses = listOf(
            Course("c1", "组件技术", "", "教2-103", 3, 10, listOf(13)),
            Course("c1", "组件技术", "", "教2-103", 3, 11, listOf(13)),
            Course("c2", "操作系统", "", "教3-201", 3, 12, listOf(13)),
            Course("c3", "计算机网络", "", "教1-108", 4, 1, listOf(13)),
            Course("c3", "计算机网络", "", "教1-108", 4, 2, listOf(13))
        )
        val startMonday = monday.minusWeeks(12)
        val table = Timetable("u", Semester("s", ""), TableKind.STUDENT, courses, 12, startMonday.toString(), 1L)
        val snapshot = widgetSnapshot("u", table, wednesday)

        // Before 19:05 class starts
        val before = ZonedDateTime.of(2026, 11, 25, 17, 32, 0, 0, SchoolZone)
        val cardBefore = formatCourseWidget(snapshot, before, autoTomorrow = false)
        assertEquals("周三 · 第 13 周", cardBefore.header)
        assertEquals("今日课程", cardBefore.modeLabel)
        assertEquals("19:05", cardBefore.featured!!.startTime)
        assertEquals("组件技术", cardBefore.featured!!.name)
        assertEquals("教2-103", cardBefore.featured!!.room)
        assertEquals("20:45 下课", cardBefore.statusCompact)
        assertEquals("下一节 · 20:45 下课", cardBefore.statusWide)
        assertEquals(2, cardBefore.remainingCount)
        assertEquals(1, cardBefore.upcoming.size)
        assertEquals("操作系统", cardBefore.upcoming.first().name)

        // During 19:05–20:45 class
        val during = ZonedDateTime.of(2026, 11, 25, 19, 30, 0, 0, SchoolZone)
        val cardDuring = formatCourseWidget(snapshot, during, autoTomorrow = false)
        assertTrue(cardDuring.featured!!.ongoing)
        assertEquals("正在上课", cardDuring.statusCompact)
        assertEquals("正在上课 · 20:45 下课", cardDuring.statusWide)

        // After all classes end with autoTomorrow = true -> shows Thursday
        val after = ZonedDateTime.of(2026, 11, 25, 22, 0, 0, 0, SchoolZone)
        val cardTomorrow = formatCourseWidget(snapshot, after, autoTomorrow = true)
        assertTrue(cardTomorrow.showingTomorrow)
        assertEquals("周四 · 明天", cardTomorrow.header)
        assertEquals("明日课程", cardTomorrow.modeLabel)
        assertEquals("08:20", cardTomorrow.featured!!.startTime)
        assertEquals("计算机网络", cardTomorrow.featured!!.name)

        // After all classes end with autoTomorrow = false -> empty done message
        val cardDone = formatCourseWidget(snapshot, after, autoTomorrow = false)
        assertNull(cardDone.featured)
        assertEquals("今天的课上完了", cardDone.emptyTitle)
        assertEquals(thursday.dayOfWeek.value, 4)
    }

    @Test fun holidayWidgetFormatsCountdownDateAndEmptyState() {
        val today = LocalDate.of(2026, 11, 25)
        val calendar = SchoolCalendar(
            "校历",
            "",
            "",
            "",
            "",
            listOf(SemesterSpan("第一学期", "2026-09-01", "2027-01-15")),
            1L
        )
        val card = formatHolidayWidget(calendar, today)
        assertFalse(card.isEmpty)
        assertEquals("52", card.daysValue)
        assertTrue(card.showDaysUnit)
        assertEquals("放寒假", card.title)
        assertEquals("1月16日 周六", card.footer)

        val empty = formatHolidayWidget(null, today)
        assertTrue(empty.isEmpty)
        assertEquals("期待下一次放假", empty.title)
    }

    @Test fun electricityWidgetFormatsBalanceLowWarningRoomAndTimestamp() {
        val now = ZonedDateTime.of(2026, 11, 25, 17, 32, 0, 0, SchoolZone)
        val sameDayMillis = ZonedDateTime.of(2026, 11, 25, 0, 1, 0, 0, SchoolZone).toInstant().toEpochMilli()
        val dorm = Dorm(
            CampusOption("1", "未央"),
            CampusOption("2", "西区"),
            CampusOption("3", "9号楼"),
            CampusOption("9328", "9328空调")
        )
        val normal = formatElectricityWidget(Electricity(dorm, "335.25", sameDayMillis), dorm, now)
        assertEquals("335.25", normal.balanceText)
        assertEquals("剩余可用电量", normal.caption)
        assertFalse(normal.isLow)
        assertEquals("9号楼 9328空调", normal.roomName)
        assertEquals("00:01 更新", normal.updatedText)

        val prevDayMillis = ZonedDateTime.of(2026, 11, 24, 21, 15, 0, 0, SchoolZone).toInstant().toEpochMilli()
        val low = formatElectricityWidget(Electricity(dorm, "6.5", prevDayMillis), dorm, now)
        assertEquals("6.50", low.balanceText)
        assertEquals("电量偏低，记得充值", low.caption)
        assertTrue(low.isLow)
        assertEquals("11/24 21:15 更新", low.updatedText)

        val unqueried = formatElectricityWidget(null, dorm, now)
        assertEquals("待查询", unqueried.balanceText)
        assertFalse(unqueried.hasBalance)
    }

    @Test fun examWidgetCardFormatsPrimaryCountdownVenueAndFollowing() {
        val anchor = LocalDate.of(2026, 11, 2)
        val now = ZonedDateTime.of(2026, 11, 25, 17, 32, 0, 0, SchoolZone)
        val exams = listOf(
            Exam("e1", "计算机组成与体系结构", "C1", "", "2026-12-29 10:20–12:20", "教2-101", "57", ""),
            Exam("e2", "操作系统", "C2", "", "2027-01-06 10:20–12:20", "教2-205", "12", ""),
            Exam("e3", "计算机网络", "C3", "", "2027-01-08 13:40–15:40", "", "", "")
        )
        val snapshot = widgetExamSnapshot("acc", "sem", 1000L, exams, anchor)
        val card = formatExamWidgetCard(snapshot, now)
        assertEquals("34", card.daysValue)
        assertTrue(card.showDaysUnit)
        assertEquals("计算机组成与体系结构", card.primary!!.course)
        assertEquals("12月29日 · 10:20", card.primary!!.displayDateTime)
        assertEquals("教2-101 座位 57", card.primary!!.displayVenue)
        assertEquals(2, card.following.size)
        assertEquals("1月6日 · 10:20", card.following[0].displayDateTime)
        assertEquals("操作系统", card.following[0].course)
        assertEquals("1月8日 · 13:40", card.following[1].displayDateTime)
        assertEquals("考场待定", card.following[1].displayRoom)
    }
}
