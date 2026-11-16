package com.xatune.app.model

/** Refresh notices compare snapshots from the same account, kind and semester scope. */
data class AcademicNotice(
    val kind: AcademicKind, val scope: String, val message: String,
    val id: Long = System.nanoTime()
) {
    val title: String get() = if (kind == AcademicKind.EXAMS) "有新的考试安排" else "有新的成绩公布"
}

fun changedGrades(previous: List<Grade>, current: List<Grade>): List<Grade> {
    if (previous.isEmpty()) return emptyList()
    val old = previous.groupBy { "${it.code}|${it.term}|${it.course}" }
    return current.filter { grade ->
        grade.score.isNotBlank() && grade.score != "--" && old["${grade.code}|${grade.term}|${grade.course}"].orEmpty().none {
            gradeFingerprint(listOf(it)) == gradeFingerprint(listOf(grade))
        }
    }
}

fun academicNotice(kind: AcademicKind, scope: String, previous: AcademicSnapshot?, current: AcademicSnapshot): AcademicNotice? {
    val items: List<String>
    val unit: String
    when (kind) {
        AcademicKind.EXAMS -> {
            val old = previous?.exams.orEmpty()
            if (old.isEmpty()) return null
            items = current.exams.filter { exam -> old.none { it.id == exam.id && it.time == exam.time && it.room == exam.room } }.map { it.course }
            unit = "场"
        }
        AcademicKind.GRADES -> {
            items = changedGrades(previous?.grades?.grades.orEmpty(), current.grades?.grades.orEmpty()).map { "${it.course} ${it.score}" }
            unit = "门"
        }
        AcademicKind.PLAN -> return null
    }
    if (items.isEmpty()) return null
    val message = items.take(2).joinToString("、") + if (items.size > 2) " 等 ${items.size} $unit" else ""
    return AcademicNotice(kind, scope, message)
}
