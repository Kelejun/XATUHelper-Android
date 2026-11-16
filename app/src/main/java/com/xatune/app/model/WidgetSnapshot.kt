package com.xatune.app.model

import java.time.LocalDate
import java.time.ZonedDateTime

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
fun nextWidgetRefresh(snapshot: WidgetCourseSnapshot, now: ZonedDateTime): Long {
    val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
    val times = snapshot.day(now.toLocalDate())?.courses.orEmpty().flatMap { block ->
        val range = Periods.range(block).split('–')
        range.mapNotNull { runCatching { now.toLocalDate().atTime(java.time.LocalTime.parse(it)).atZone(now.zone) }.getOrNull() }
    }
    return (times + midnight).filter { it > now }.minOrNull()!!.toInstant().toEpochMilli()
}
