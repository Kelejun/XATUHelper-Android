package com.xatune.app.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class CourseProgressLesson(
    val id: String,
    val name: String,
    val room: String,
    val start: Instant,
    val end: Instant
)

data class CourseProgressStage(
    val lesson: CourseProgressLesson,
    val since: Instant,
    val until: Instant,
    val previousRoom: String? = null
) {
    val preparing: Boolean get() = until == lesson.start

    fun percentAt(now: Instant): Int {
        val duration = until.toEpochMilli() - since.toEpochMilli()
        val elapsed = (now.toEpochMilli() - since.toEpochMilli()).coerceIn(0, duration)
        return (elapsed * 100 / duration).toInt()
    }
}

data class CourseProgressPlan(val stage: CourseProgressStage? = null, val nextBoundary: Instant? = null)

object CourseProgressPlanner {
    private const val PREPARATION_SECONDS = 30 * 60L
    private val roomSuffix = Regex("""(?:\([^()]*\)|（[^（）]*）)$""")
    private val untimedLabels = listOf("网课", "网络课程", "占位")

    fun plan(table: Timetable?, now: Instant): CourseProgressPlan {
        if (table?.kind != TableKind.STUDENT || table.courses.isEmpty()) return CourseProgressPlan()
        val today = now.atZone(SchoolZone).toLocalDate()
        val lessons = lessonsOn(table, today)
        val current = lessons.filter { it.start <= now && now < it.end }.minByOrNull { it.end }
        if (current != null) {
            val next = lessons.firstOrNull { it.start > now && it.end < current.end }
            return CourseProgressPlan(CourseProgressStage(current, current.start, current.end), next?.start ?: current.end)
        }

        val nextToday = lessons.firstOrNull { it.start > now }
        if (nextToday != null) {
            val earliest = nextToday.start.minusSeconds(PREPARATION_SECONDS)
            val noon = today.atTime(LocalTime.NOON).atZone(SchoolZone).toInstant()
            val previous = lessons.filter {
                it.end >= earliest && it.end <= now && (nextToday.start < noon || it.end > noon)
            }.maxByOrNull { it.end }
            val begins = previous?.end ?: earliest
            if (now < begins) return CourseProgressPlan(nextBoundary = begins)
            return CourseProgressPlan(CourseProgressStage(nextToday, begins, nextToday.start, previous?.room), nextToday.start)
        }

        val monday = LocalDate.parse(table.monday)
        val termEnd = monday.plusWeeks(30)
        val next = generateSequence(maxOf(today.plusDays(1), monday)) { it.plusDays(1) }
            .takeWhile { it < termEnd }
            .firstNotNullOfOrNull { lessonsOn(table, it).firstOrNull() }
        return CourseProgressPlan(nextBoundary = next?.start?.minusSeconds(PREPARATION_SECONDS))
    }

    private fun lessonsOn(table: Timetable, date: LocalDate): List<CourseProgressLesson> =
        coursesOn(table, date).mapNotNull { block ->
            if (isUntimedCourse(block.name, block.room) || untimedLabels.any { it in block.room } || block.name in untimedLabels) {
                return@mapNotNull null
            }
            val range = Periods.range(block)
            val times = runCatching {
                LocalTime.parse(range.substringBefore('–')) to LocalTime.parse(range.substringAfter('–'))
            }.getOrNull() ?: return@mapNotNull null
            val (start, end) = times
            if (start >= end) return@mapNotNull null
            val room = block.room.trim()
            val shortRoom = roomSuffix.replace(room, "").trim().ifEmpty { room.ifEmpty { "教室待定" } }
            CourseProgressLesson(
                "${table.account}|${table.semester.id}|${block.id}|$date", block.name, shortRoom,
                date.atTime(start).atZone(SchoolZone).toInstant(), date.atTime(end).atZone(SchoolZone).toInstant()
            )
        }.sortedBy { it.start }
}
