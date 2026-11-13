package com.xatune.app.model

import com.google.gson.Gson
import com.google.gson.JsonElement
import java.security.MessageDigest
import java.time.*
import java.time.temporal.ChronoUnit

enum class InformationKind(val hours: Long) { TIMETABLE(168), PLAN(192), GRADES(216), EXAMS(240) }
data class RefreshTerm(val start: LocalDate, val end: LocalDate? = null)
object InformationRefreshPolicy {
    fun needsDailyRefresh(at: Long?, now: ZonedDateTime = ZonedDateTime.now()): Boolean = at == null || Instant.ofEpochMilli(at).atZone(now.zone).toLocalDate() != now.toLocalDate()
    fun terms(calendars: List<SchoolCalendar>, semesters: List<Semester>, fallback: String?): List<RefreshTerm> {
        val terms = calendars.asReversed().flatMap { it.semesters }.mapNotNull { span ->
            val start = date(span.start) ?: return@mapNotNull null
            val end = date(span.end)?.takeIf { it >= start } ?: return@mapNotNull null
            RefreshTerm(start, end)
        }.fold(mutableListOf<RefreshTerm>()) { result, term -> result.apply { if (none { kotlin.math.abs(ChronoUnit.DAYS.between(it.start, term.start)) < 30 }) add(term) } }
        fun add(start: LocalDate?) { if (start != null && terms.none { kotlin.math.abs(ChronoUnit.DAYS.between(it.start, start)) < 30 }) terms.add(RefreshTerm(start)) }
        semesters.forEach { add(date(it.startDate?.take(10))) }
        calendars.lastOrNull()?.semesters?.minByOrNull { it.start }?.let { add(date(it.start)?.plusDays(364)) }
        if (terms.isEmpty()) add(date(fallback))
        return terms.sortedBy { it.start }
    }
    fun gradesEnabled(terms: List<RefreshTerm>, today: LocalDate): Boolean {
        val term = terms.lastOrNull { it.start <= today } ?: return false
        return today >= term.start.minusDays((term.start.dayOfWeek.value - 1).toLong()).plusWeeks(11)
    }
    fun vacation(terms: List<RefreshTerm>, today: LocalDate): Boolean = terms.lastOrNull { it.start <= today }?.end?.let { today > it } ?: false
    fun stale(kind: InformationKind, at: Long?, reduced: Boolean, terms: List<RefreshTerm>, now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        if (reduced) return at == null || now.toInstant().toEpochMilli() - at >= kind.hours * 3_600_000
        val enabled = when (kind) { InformationKind.PLAN -> false; InformationKind.GRADES -> gradesEnabled(terms, now.toLocalDate()); InformationKind.EXAMS -> !vacation(terms, now.toLocalDate()); else -> true }
        return enabled && needsDailyRefresh(at, now)
    }
    // null waits, zero bootstraps missing dates, positive values persist the target school year.
    fun calendarTarget(starts: List<LocalDate>, pendingYear: Int?, today: LocalDate): Int? {
        val ordered = starts.sorted()
        if (ordered.size < 2) return pendingYear ?: 0
        val first = ordered.first()
        if (pendingYear != null && first.year < pendingYear) return pendingYear
        val next = ordered.getOrNull(2) ?: first.plusDays(364)
        return if (today >= next.minusDays(30)) first.year + 1 else null
    }
    fun date(value: String?): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
}

fun gradeFingerprint(grades: List<Grade>): String {
    val gson = Gson()
    fun canonical(value: JsonElement): String = when {
        value.isJsonObject -> value.asJsonObject.keySet().sorted().joinToString(",", "{", "}") { gson.toJson(it) + ":" + canonical(value.asJsonObject[it]) }
        value.isJsonArray -> value.asJsonArray.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
    val records = grades.map { grade -> canonical(gson.toJsonTree(grade.copy(id = "", parts = grade.parts.sortedWith(compareBy({ it.name }, { it.score }))))) }.sorted()
    return MessageDigest.getInstance("SHA-256").digest(records.joinToString(",", "[", "]").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
