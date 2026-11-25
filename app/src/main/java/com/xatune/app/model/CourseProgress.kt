package com.xatune.app.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class CourseProgressLesson(
    val id: String,
    val name: String,
    val room: String,
    val start: Instant,
    val end: Instant,
    val colorIndex: Int = 1
)

enum class CourseProgressCompletion { STARTED, ENDED }

data class CourseProgressStage(
    val lesson: CourseProgressLesson,
    val since: Instant,
    val until: Instant,
    val previousRoom: String? = null,
    val completion: CourseProgressCompletion? = null
) {
    val preparing: Boolean get() = completion == null && until == lesson.start
    val finished: Boolean get() = completion != null
    val phase: String get() = when {
        completion == CourseProgressCompletion.STARTED -> "已上课"
        completion == CourseProgressCompletion.ENDED -> "已下课"
        !preparing -> "上课中"
        previousRoom != null -> "课间"
        else -> "课前"
    }
    val routeOrigin: String? get() = previousRoom?.takeIf { preparing && it != lesson.room }

    fun percentAt(now: Instant): Int {
        if (finished) return 100
        val duration = (until.toEpochMilli() - since.toEpochMilli()).coerceAtLeast(1)
        val elapsed = (now.toEpochMilli() - since.toEpochMilli()).coerceIn(0, duration)
        return (elapsed * 100 / duration).toInt()
    }
}

data class CourseProgressPlan(val stage: CourseProgressStage? = null, val nextBoundary: Instant? = null)

object CourseProgressPlanner {
    private const val PREPARATION_SECONDS = 30 * 60L
    private val roomSuffix = Regex("""(?:\([^()]*\)|（[^（）]*）)$""")
    private val untimedLabels = listOf("网课", "网络课程", "占位")

    private const val COMPLETION_SECONDS = 60L

    fun plan(table: Timetable?, now: Instant, preparationEnabled: Boolean = true, classEnabled: Boolean = true): CourseProgressPlan {
        if ((!preparationEnabled && !classEnabled) || table?.kind != TableKind.STUDENT || table.courses.isEmpty()) return CourseProgressPlan()
        val today = now.atZone(SchoolZone).toLocalDate()
        val lessons = lessonsOn(table, today)
        val noon = today.atTime(LocalTime.NOON).atZone(SchoolZone).toInstant()
        val stages = lessons.flatMap { lesson ->
            val earliest = lesson.start.minusSeconds(PREPARATION_SECONDS)
            val previous = lessons.filter {
                it.end >= earliest && it.end <= lesson.start && it.id != lesson.id &&
                    (lesson.start < noon || it.end > noon)
            }.maxByOrNull { it.end }
            buildList {
                if (preparationEnabled) {
                    val begins = previous?.end ?: earliest
                    if (begins < lesson.start) add(CourseProgressStage(lesson, begins, lesson.start, previous?.room))
                }
                if (classEnabled) {
                    add(CourseProgressStage(lesson, lesson.start, lesson.end))
                    add(CourseProgressStage(lesson, lesson.end, lesson.end.plusSeconds(COMPLETION_SECONDS), completion = CourseProgressCompletion.ENDED))
                } else if (preparationEnabled) {
                    add(CourseProgressStage(lesson, lesson.start, lesson.start.plusSeconds(COMPLETION_SECONDS), completion = CourseProgressCompletion.STARTED))
                }
            }
        }
        // An active lesson or the next preparation always takes priority over a completion.
        val stage = stages.filter { it.since <= now && now < it.until }
            .sortedWith(compareBy<CourseProgressStage> { it.finished }.thenBy { it.preparing }.thenBy { it.until })
            .firstOrNull()
        val futureStages = stages.filter {
            stage == null || stage.finished || stage.preparing || (!it.finished && !it.preparing)
        }
        val nextBoundary = (futureStages.map { it.since } + listOfNotNull(stage?.until)).filter { it > now }.minOrNull()
        if (stage != null || nextBoundary != null) return CourseProgressPlan(stage, nextBoundary)

        val monday = LocalDate.parse(table.monday)
        val termEnd = monday.plusWeeks(30)
        val next = generateSequence(maxOf(today.plusDays(1), monday)) { it.plusDays(1) }
            .takeWhile { it < termEnd }
            .firstNotNullOfOrNull { lessonsOn(table, it).firstOrNull() }
        return CourseProgressPlan(nextBoundary = next?.start?.let {
            if (preparationEnabled) it.minusSeconds(PREPARATION_SECONDS) else it
        })
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
                date.atTime(start).atZone(SchoolZone).toInstant(), date.atTime(end).atZone(SchoolZone).toInstant(),
                table.courses.map { it.key }.distinct().sorted().indexOf(block.key).coerceAtLeast(0) % 12
            )
        }.sortedBy { it.start }
}
