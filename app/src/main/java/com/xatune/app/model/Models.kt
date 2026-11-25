package com.xatune.app.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

val SchoolZone: ZoneId = ZoneId.of("Asia/Shanghai")
fun schoolToday(): LocalDate = LocalDate.now(SchoolZone)
fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
fun mondayForWeek(week: Int, today: LocalDate = schoolToday()): LocalDate = mondayOf(today).minusWeeks((week.coerceIn(1, 30) - 1).toLong())
fun weekOf(start: LocalDate, date: LocalDate): Int = (Math.floorDiv(ChronoUnit.DAYS.between(start, date), 7).toInt() + 1).coerceIn(1, 30)

data class Semester(val id: String, val name: String, val startDate: String? = null)
enum class TableKind(val wire: String, val title: String) { STUDENT("std", "学生课表"), CLASS("class", "班级课表") }
data class Shell(val studentId: String, val classId: String?, val semesterId: String, val tagId: String, val projectId: String)
data class Course(val key: String, val name: String, val teacher: String, val room: String, val day: Int, val period: Int, val weeks: List<Int>, val localId: String? = null, val fixedDates: Boolean = false)
data class CourseBlock(val key: String, val name: String, val teacher: String, val room: String, val day: Int, val start: Int, val end: Int, val weeks: List<Int>) {
    val id: String get() = "$key|$day|$start|$room|$teacher"
    val periodText: String get() = if (start == end) "第 $start 节" else "第 $start–$end 节"
}
data class Timetable(val account: String, val semester: Semester, val kind: TableKind, val courses: List<Course>, val unitCount: Int, val monday: String, val fetchedAt: Long)
data class ParsedTable(val courses: List<Course>, val unitCount: Int, val rawCount: Int)

// Filter by week BEFORE merging periods; otherwise alternating weeks create phantom lessons.
fun blocksFor(courses: List<Course>, week: Int): List<CourseBlock> = courses.filter { week in it.weeks }
    .groupBy { listOf(it.key, it.day, it.room, it.teacher) }.values.flatMap { entries ->
        val runs = mutableListOf<MutableList<Course>>()
        entries.distinctBy { it.period }.sortedBy { it.period }.forEach { course ->
            if (runs.lastOrNull()?.last()?.period?.plus(1) == course.period) runs.last().add(course)
            else runs.add(mutableListOf(course))
        }
        runs.map { run ->
            val first = run.first()
            CourseBlock(first.key, first.name, first.teacher, first.room, first.day, first.period, run.last().period, run.flatMap { it.weeks }.distinct().sorted())
        }
    }.sortedWith(compareBy({ it.day }, { it.start }, { it.key }))

/** Keep calendar consumers outside the teaching range empty; weekOf is for the bounded UI selector. */
fun coursesOn(table: Timetable?, date: LocalDate): List<CourseBlock> {
    if (table == null) return emptyList()
    val week = Math.floorDiv(ChronoUnit.DAYS.between(LocalDate.parse(table.monday), date), 7).toInt() + 1
    return if (week in 1..30) blocksFor(table.courses, week).filter { it.day == date.dayOfWeek.value } else emptyList()
}

object Periods {
    val starts = listOf("08:20", "09:15", "10:20", "11:15", "14:00", "14:55", "16:00", "16:55", "18:10", "19:05", "20:00", "20:55")
    val ends = listOf("09:05", "10:00", "11:05", "12:00", "14:45", "15:40", "16:45", "17:40", "18:55", "19:50", "20:45", "21:40")
    // Display-only hint: actual ranges and reminders retain the building-specific schedule.
    fun gridStartLabel(period: Int): String = if (period == 3) "10:10/10:20" else starts.getOrElse(period - 1) { "" }
    fun range(block: CourseBlock) = if (block.start == 3 && block.end == 4 && "教3" in block.room) "10:10–11:40" else "${starts.getOrElse(block.start - 1) { "--:--" }}–${ends.getOrElse(block.end - 1) { "--:--" }}"
}
