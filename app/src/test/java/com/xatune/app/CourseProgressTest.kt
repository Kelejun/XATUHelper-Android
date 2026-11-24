package com.xatune.app

import com.google.gson.Gson
import com.xatune.app.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class CourseProgressTest {
    private val monday = LocalDate.of(2026, 11, 23)

    @Test fun preparationAndMergedClassUseActualBuildingTimes() {
        val table = table(course("实验", 3, "教3-101（东区）"), course("实验", 4, "教3-101（东区）"))

        assertEquals(CourseProgressPlan(nextBoundary = time("09:40")), plan(table, "09:39"))
        val preparation = plan(table, "09:40").stage!!
        assertTrue(preparation.preparing)
        assertEquals("教3-101", preparation.lesson.room)
        assertEquals(0, preparation.percentAt(time("09:40")))
        assertEquals(50, preparation.percentAt(time("09:55")))
        assertEquals(time("10:10"), preparation.until)

        val inClass = plan(table, "10:10")
        assertFalse(inClass.stage!!.preparing)
        assertEquals(0, inClass.stage.percentAt(time("10:10")))
        assertEquals(50, inClass.stage.percentAt(time("10:55")))
        assertEquals(time("11:40"), inClass.nextBoundary)
        assertEquals(CourseProgressPlan(), plan(table, "11:40"))
    }

    @Test fun changeOfRoomStartsWhenThePreviousClassEnds() {
        val table = table(course("数学", 1, "教1-101"), course("英语", 2, "教2-201"))

        assertEquals("数学", plan(table, "09:04").stage!!.lesson.name)
        val transfer = plan(table, "09:05").stage!!
        assertEquals("教1-101", transfer.previousRoom)
        assertEquals("教2-201", transfer.lesson.room)
        assertEquals(time("09:05"), transfer.since)
        assertEquals(50, transfer.percentAt(time("09:10")))
        assertEquals(time("09:15"), transfer.until)
        assertNull(plan(table, "09:15").stage!!.previousRoom)
    }

    @Test fun lunchAndDifferentSchoolDaysDoNotCarryOverThePreviousRoom() {
        val tomorrow = monday.plusDays(1)
        val table = table(course("数学", 4, "教1"), course("英语", 5, "教2"), course("物理", 1, "教3", day = 2))

        assertEquals(CourseProgressPlan(nextBoundary = time("13:30")), plan(table, "12:00"))
        val afterLunch = plan(table, "13:30").stage!!
        assertEquals("教2", afterLunch.lesson.room)
        assertNull(afterLunch.previousRoom)
        assertEquals(CourseProgressPlan(nextBoundary = time("07:50", tomorrow)), plan(table, "14:45"))
    }

    @Test fun usesLocalEditsAndSkipsUntimedCoursesAndEmptyWeeks() {
        val base = table(course("远程", 1, "网课"), course("实践环节选课", 2, ""), course("物理", 1, "教1", weeks = listOf(3)))
        val add = TimetableEdit(kind = EditKind.ADD, added = ManualCourse("自习", "", "", listOf(1), 3, 4, 1, 1))
        val edited = editTimetable(base, listOf(add)).table

        assertEquals(time("09:50"), plan(edited, "08:00").nextBoundary)
        assertEquals("教室待定", plan(edited, "09:50").stage!!.lesson.room)
        assertEquals(time("07:50", monday.plusWeeks(2)), plan(edited, "12:00").nextBoundary)
        assertEquals(CourseProgressPlan(), plan(edited.copy(kind = TableKind.CLASS), "09:50"))
    }

    @Test fun anOverlappingClassThatEndsFirstTakesOverAtItsStart() {
        val table = table(
            course("普通课", 2, "教1"), course("普通课", 3, "教1"), course("普通课", 4, "教1"),
            course("特定作息", 3, "教3"), course("特定作息", 4, "教3")
        )

        assertEquals(time("10:10"), plan(table, "09:30").nextBoundary)
        assertEquals("特定作息", plan(table, "10:10").stage!!.lesson.name)
        assertEquals(time("11:40"), plan(table, "11:15").nextBoundary)
        assertEquals("普通课", plan(table, "11:40").stage!!.lesson.name)
    }

    @Test fun legacyReminderSettingsKeepCourseProgressOff() {
        val options = Gson().fromJson("""{"classes":true,"classLead":15}""", ReminderSettings::class.java)
        assertTrue(options.classes)
        assertFalse(options.courseProgress)
    }

    private fun time(clock: String, day: LocalDate = monday): Instant = day.atTime(LocalTime.parse(clock)).atZone(SchoolZone).toInstant()
    private fun plan(table: Timetable, clock: String) = CourseProgressPlanner.plan(table, time(clock))
    private fun table(vararg courses: Course) = Timetable("fixture", Semester("term", "学期"), TableKind.STUDENT, courses.toList(), 12, monday.toString(), 0)
    private fun course(name: String, period: Int, room: String, day: Int = 1, weeks: List<Int> = listOf(1)) =
        Course(name, name, "", room, day, period, weeks)
}
