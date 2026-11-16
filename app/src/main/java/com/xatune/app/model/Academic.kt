package com.xatune.app.model

import java.time.LocalDate

enum class AcademicKind(val title: String, val hours: Long) {
    EXAMS("考试安排", 240), GRADES("成绩", 216), PLAN("学业完成情况", 192)
}
data class Exam(val id: String, val course: String, val code: String, val name: String, val time: String, val room: String, val seat: String, val note: String) {
    /** Match iOS: calendar days remaining, today stays visible all day; no label for past/unknown dates. */
    fun remainingText(anchor: LocalDate = schoolToday(), today: LocalDate = schoolToday()): String {
        val day = date(anchor) ?: return ""
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, day)
        return when { days > 0 -> "还有 $days 天"; days == 0L -> "今天"; else -> "" }
    }
    fun date(anchor: LocalDate = schoolToday()): LocalDate? {
        val full = Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})""").find(time)
        if (full != null) return runCatching { LocalDate.of(full.groupValues[1].toInt(), full.groupValues[2].toInt(), full.groupValues[3].toInt()) }.getOrNull()
        val short = Regex("""(\d{1,2})月(\d{1,2})日""").find(time) ?: return null
        val month = short.groupValues[1].toInt()
        val year = anchor.year + if (month < 8 && anchor.monthValue >= 8) 1 else if (month >= 8 && anchor.monthValue < 8) -1 else 0
        return runCatching { LocalDate.of(year, month, short.groupValues[2].toInt()) }.getOrNull()
    }
}
/** iOS ordering: future dates ascending, finished dates descending, unknown dates last. */
fun orderedExams(exams: List<Exam>, anchor: LocalDate, today: LocalDate = schoolToday()): List<Exam> {
    val upcoming = exams.filter { it.date(anchor)?.let { date -> date >= today } == true }
        .sortedWith(compareBy({ it.date(anchor) }, { it.time }))
    val bottom = exams.filter { it.date(anchor)?.let { date -> date < today } != false }
        .sortedWith(compareBy<Exam> { it.date(anchor) == null }.thenByDescending { it.date(anchor) }.thenBy { it.time })
    return upcoming + bottom
}
fun matchesCourse(name: String, code: String, query: String): Boolean {
    val keyword = query.trim()
    return keyword.isEmpty() || name.contains(keyword, true) || code.contains(keyword, true)
}

data class GradePart(val name: String, val score: String)
data class Grade(val id: String, val course: String, val code: String, val credit: String, val score: String, val gpa: String, val term: String, val parts: List<GradePart>)
data class GradeSummary(val count: Int? = null, val credits: Double? = null, val gpa: Double? = null)
data class GradePage(val grades: List<Grade>, val summary: GradeSummary)
data class PlanCourse(val id: String, val code: String, val name: String, val required: Double, val earned: Double, val score: String, val passed: Boolean)
data class PlanSection(val id: String, val title: String, val required: Double, val earned: Double, val courses: List<PlanCourse>, val children: List<PlanSection>) {
    fun allCourses(): List<PlanCourse> = courses + children.flatMap { it.allCourses() }
}
data class StudyPlan(val studentName: String, val major: String, val gpa: String, val required: Double, val earned: Double, val sections: List<PlanSection>)
data class CourseInfo(val code: String, val name: String, val nature: String, val credit: String, val hours: String, val term: String)
data class AcademicSnapshot(
    val fetchedAt: Long = System.currentTimeMillis(), val exams: List<Exam> = emptyList(),
    val grades: GradePage? = null, val plan: StudyPlan? = null, val catalog: List<CourseInfo> = emptyList(),
    val catalogWarning: String? = null, val account: String? = null, val scope: String? = null
)
fun gradeChanges(previous: List<Grade>, current: List<Grade>): Int = changedGrades(previous, current).size

fun normalizedExamTime(raw: String): String {
    val match = Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""").find(raw) ?: return raw.trim()
    val date = runCatching { LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) }.getOrNull() ?: return raw.trim()
    val rest = raw.removeRange(match.range).trim().replace(Regex("""\s+"""), " ")
    return "$date $rest".trim()
}
