package com.xatune.app.model

import java.time.LocalDate

data class CalendarCorrection(val semesters: List<SemesterSpan>, val notes: List<String> = emptyList()) {
    fun normalized() = copy(semesters = semesters.map { it.copy(label = it.label.trim()) }.sortedBy { it.start }, notes = notes.map(String::trim).filter(String::isNotEmpty))
    fun error(): String? {
        if (semesters.isEmpty()) return "至少保留一个学期"
        val sorted = normalized().semesters
        var previousEnd: LocalDate? = null
        for (span in sorted) {
            if (span.label.isBlank()) return "学期名称不能为空"
            val start = date(span.start) ?: return "开学日期须为有效日期（yyyy-MM-dd）"
            val end = date(span.end) ?: return "结课日期须为有效日期（yyyy-MM-dd）"
            if (end < start) return "结课日期不能早于开学日期"
            if (previousEnd?.let { start <= it } == true) return "学期日期不能重叠"
            previousEnd = end
        }
        return null
    }
    companion object {
        private fun date(value: String): LocalDate? = runCatching { LocalDate.parse(value).takeIf { it.toString() == value } }.getOrNull()
        fun original(calendar: SchoolCalendar) = CalendarCorrection(calendar.semesters, calendar.notes.orEmpty()).normalized()
    }
}
