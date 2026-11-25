package com.xatune.app.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class WidgetDay(val date: String, val week: Int, val courses: List<CourseBlock>)
data class WidgetCourseSnapshot(val account: String, val source: String, val days: List<WidgetDay>) {
    fun day(date: LocalDate): WidgetDay? = days.firstOrNull { it.date == date.toString() }
}
fun widgetSource(table: Timetable?): String = table?.let { "${it.account}|${it.semester.id}|${it.fetchedAt}|${it.monday}|${it.courses.hashCode()}" }.orEmpty()
fun widgetSnapshot(account: String, table: Timetable?, today: LocalDate): WidgetCourseSnapshot = WidgetCourseSnapshot(account, widgetSource(table),
    if (account.isBlank() || table == null || table.account != account || table.kind != TableKind.STUDENT) emptyList()
    else (0L..13L).map { offset ->
        val day = today.plusDays(offset)
        WidgetDay(day.toString(), weekOf(LocalDate.parse(table.monday), day), coursesOn(table, day))
    })

data class WidgetExamEntry(
    val id: String,
    val course: String,
    val date: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val room: String? = null,
    val seat: String? = null
) {
    val localDate: LocalDate? get() = runCatching { LocalDate.parse(date) }.getOrNull()
    val endAtMillis: Long? get() = localDate?.let { d ->
        val end = endTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        if (end != null) d.atTime(end).atZone(SchoolZone).toInstant().toEpochMilli()
        else d.plusDays(1).atStartOfDay(SchoolZone).toInstant().toEpochMilli()
    }
    val displayRoom: String get() = room?.trim()?.takeIf { it.isNotEmpty() } ?: "考场待定"
}

data class WidgetExamSnapshot(
    val account: String,
    val semesterId: String,
    val fetchedAt: Long,
    val exams: List<WidgetExamEntry>
) {
    fun upcoming(now: ZonedDateTime): List<WidgetExamEntry> {
        val nowMillis = now.toInstant().toEpochMilli()
        return exams.filter { entry ->
            val end = entry.endAtMillis ?: return@filter false
            end > nowMillis
        }.sortedWith(compareBy<WidgetExamEntry>({ it.date }, { it.startTime ?: "99:99" }, { it.course }))
    }
}

private val examClockPattern = Regex("""(?<!\d)(\d{1,2})[:：](\d{2})(?!\d)""")

fun widgetExamSnapshot(
    account: String,
    semesterId: String,
    fetchedAt: Long,
    exams: List<Exam>,
    anchor: LocalDate
): WidgetExamSnapshot {
    if (account.isBlank()) return WidgetExamSnapshot("", "", 0L, emptyList())
    val entries = exams.mapNotNull { exam ->
        val date = exam.date(anchor) ?: return@mapNotNull null
        val times = examClockPattern.findAll(exam.time).mapNotNull { match ->
            runCatching { LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()) }.getOrNull()
        }.toList()
        val start = times.firstOrNull()
        val end = times.getOrNull(1)?.takeIf { start == null || !it.isBefore(start) }
        WidgetExamEntry(
            id = exam.id,
            course = exam.course.trim(),
            date = date.toString(),
            startTime = start?.let { "%02d:%02d".format(it.hour, it.minute) },
            endTime = end?.let { "%02d:%02d".format(it.hour, it.minute) },
            room = exam.room.trim().takeIf { it.isNotEmpty() },
            seat = exam.seat.trim().takeIf { it.isNotEmpty() }
        )
    }.sortedWith(compareBy<WidgetExamEntry>({ it.date }, { it.startTime ?: "99:99" }, { it.course }))
    return WidgetExamSnapshot(account, semesterId, fetchedAt, entries)
}

data class WidgetExamDisplay(val title: String, val body: String, val subtitle: String)

fun formatExamWidget(snapshot: WidgetExamSnapshot?, now: ZonedDateTime, wide: Boolean): WidgetExamDisplay {
    if (snapshot == null || snapshot.account.isBlank()) {
        return WidgetExamDisplay("考试倒计时", "登录后查看考试安排", "点击打开 XATune")
    }
    val upcoming = snapshot.upcoming(now)
    if (upcoming.isEmpty()) {
        val subtitle = if (snapshot.fetchedAt == 0L) "点击同步当前学期考试" else "当前学期暂无未结束的考试"
        return WidgetExamDisplay("考试倒计时", "暂无待考安排", subtitle)
    }
    val first = upcoming.first()
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), first.localDate!!)
    val countdown = if (days <= 0L) "今天" else "还有 $days 天"
    val primaryWhen = listOfNotNull(first.date, first.startTime).joinToString(" ")
    val roomSeat = listOfNotNull(first.displayRoom, first.seat?.let { "座位 $it" }).joinToString(" · ")
    val bodyLines = mutableListOf("${first.course}\n$primaryWhen")
    if (wide) {
        upcoming.drop(1).take(2).forEach { next ->
            val nextWhen = listOfNotNull(next.date, next.startTime).joinToString(" ")
            bodyLines += "后续 · ${next.course} · $nextWhen"
        }
    }
    return WidgetExamDisplay(
        title = "考试倒计时 · $countdown",
        body = bodyLines.joinToString("\n"),
        subtitle = roomSeat
    )
}

fun nextWidgetRefresh(snapshot: WidgetCourseSnapshot, now: ZonedDateTime, examSnapshot: WidgetExamSnapshot? = null): Long {
    val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
    val times = snapshot.day(now.toLocalDate())?.courses.orEmpty().flatMap { block ->
        val range = Periods.range(block).split('–')
        range.mapNotNull { runCatching { now.toLocalDate().atTime(LocalTime.parse(it)).atZone(now.zone) }.getOrNull() }
    }
    val nowMillis = now.toInstant().toEpochMilli()
    val courseAndMidnight = (times + midnight).map { it.toInstant().toEpochMilli() }
    val examEnds = examSnapshot?.exams.orEmpty().mapNotNull { it.endAtMillis }
    return (courseAndMidnight + examEnds).filter { it > nowMillis }.minOrNull()!!
}
