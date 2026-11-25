package com.xatune.app.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.time.temporal.ChronoUnit

data class CampusOption(val id: String, val name: String)
data class Dorm(val project: CampusOption, val area: CampusOption, val building: CampusOption, val room: CampusOption) {
    val key: String get() = listOf(project.id, area.id, building.id, room.id).joinToString("|")
    val label: String get() = "${area.name.trim()} · ${building.name.trim()} ${room.name.trim()}"
}
data class Electricity(val dorm: Dorm, val balance: String, val checkedAt: Long)
data class DailyBalance(val day: String, val balance: String)
fun balanceAmount(oddl: String?, suml: String?): String {
    val used = oddl?.toBigDecimalOrNull() ?: throw IllegalArgumentException("电费余额字段缺失")
    val amount = if (suml == null) used else (suml.toBigDecimalOrNull() ?: throw IllegalArgumentException("电费余额字段异常")) - used
    return amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
fun dailyDeduction(previous: DailyBalance?, current: DailyBalance): BigDecimal? {
    if (previous == null || runCatching { ChronoUnit.DAYS.between(LocalDate.parse(previous.day), LocalDate.parse(current.day)) }.getOrNull() != 1L) return null
    val change = previous.balance.toBigDecimalOrNull()?.minus(current.balance.toBigDecimalOrNull() ?: return null) ?: return null
    return change.takeIf { it.signum() >= 0 }
}
data class SemesterSpan(val label: String, val start: String, val end: String)
data class SchoolCalendar(val title: String, val pageUrl: String, val imageUrl: String, val imagePath: String, val rawText: String, val semesters: List<SemesterSpan>, val fetchedAt: Long, val coverageEnd: String? = null, val recognitionVersion: Int = 0, val notes: List<String> = emptyList())
data class SchoolBreak(val title: String, val start: LocalDate, val end: LocalDate?)
fun schoolBreaks(calendar: SchoolCalendar?): List<SchoolBreak> = calendar?.semesters?.mapNotNull { span ->
    val end = runCatching { LocalDate.parse(span.end) }.getOrNull() ?: return@mapNotNull null
    val start = end.plusDays(1)
    val next = calendar.semesters.mapNotNull { runCatching { LocalDate.parse(it.start) }.getOrNull() }.filter { it > start }.minOrNull()
    val until = next?.minusDays(1) ?: (if (calendar.semesters.size == 2) runCatching { LocalDate.parse(calendar.semesters.minBy { it.start }.start).plusDays(363) }.getOrNull() else runCatching { LocalDate.parse(calendar.coverageEnd) }.getOrNull())?.takeIf { it >= start }
    SchoolBreak(if (start.monthValue in 1..3) "寒假" else "暑假", start, until)
}?.sortedBy { it.start }.orEmpty()
fun nextBreak(calendar: SchoolCalendar?, today: LocalDate = schoolToday()): SchoolBreak? = schoolBreaks(calendar).firstOrNull {
    it.end?.let { end -> end >= today } ?: (it.start >= today)
}

data class CampusEvent(val title: String, val date: LocalDate)
fun nextCampusEvent(calendar: SchoolCalendar?, today: LocalDate = schoolToday()): CampusEvent? {
    val starts = calendar?.semesters.orEmpty().mapNotNull { span ->
        runCatching { CampusEvent("${span.label}开学", LocalDate.parse(span.start)) }.getOrNull()
    }
    val breaks = schoolBreaks(calendar).map { CampusEvent("放${it.title}", it.start) }
    return (starts + breaks).filter { it.date >= today }.minByOrNull { it.date }
}

data class ReminderSettings(val classes: Boolean = false, val classLead: Int = 30, val exams: Boolean = false, val eve: Boolean = true, val eveMinutes: Int = 1260, val morning: Boolean = true, val morningMinutes: Int = 480, val before: Boolean = true, val examLead: Int = 30)
enum class ReminderPromptKind { CLASSES, EXAMS }
object ReminderPrompt {
    fun apply(
        current: ReminderSettings,
        kind: ReminderPromptKind,
        confirm: Boolean,
        draft: ReminderSettings
    ): ReminderSettings {
        if (!confirm) return current
        return when (kind) {
            ReminderPromptKind.CLASSES -> current.copy(
                classes = true,
                classLead = draft.classLead.coerceIn(0, 180)
            )
            ReminderPromptKind.EXAMS -> current.copy(
                exams = true,
                eve = draft.eve,
                eveMinutes = draft.eveMinutes.coerceIn(0, 1439),
                morning = draft.morning,
                morningMinutes = draft.morningMinutes.coerceIn(0, 1439),
                before = draft.before,
                examLead = draft.examLead.coerceIn(0, 180)
            )
        }
    }
}
data class Reminder(val id: String, val at: Long, val title: String, val body: String, val page: String, val expiresAt: Long? = null)
object ClassNotificationExpiry {
    const val EXTRA_ENDS_AT = "class_ends_at"
    private val timeRangePattern = Regex("""\b(\d{1,2}:\d{2})\s*[–—\-~至]\s*(\d{1,2}:\d{2})\b""")

    fun resolveEndsAt(tag: String?, explicitEndsAt: Long?, body: CharSequence?): Long? {
        if (tag == null || !tag.startsWith("class|")) return null
        if (explicitEndsAt != null && explicitEndsAt > 0L) return explicitEndsAt
        val date = runCatching { LocalDate.parse(tag.substringAfterLast('|', "")) }.getOrNull() ?: return null
        val endText = body?.let { timeRangePattern.find(it)?.groupValues?.getOrNull(2) } ?: return null
        val endTime = runCatching { LocalTime.parse(endText.padStart(5, '0')) }.getOrNull() ?: return null
        return date.atTime(endTime).atZone(SchoolZone).toInstant().toEpochMilli()
    }

    fun isExpired(tag: String?, explicitEndsAt: Long?, body: CharSequence?, nowMillis: Long): Boolean {
        val endsAt = resolveEndsAt(tag, explicitEndsAt, body) ?: return false
        return nowMillis >= endsAt
    }
}
object ReminderPlanner {
    fun plan(table: Timetable?, exams: List<Exam>, options: ReminderSettings, now: ZonedDateTime = ZonedDateTime.now(SchoolZone)): List<Reminder> {
        val result = mutableListOf<Reminder>()
        val limit = now.plusDays(7).toInstant().toEpochMilli()
        fun add(id: String, time: ZonedDateTime, title: String, body: String, page: String, expiresAt: Long? = null) {
            val at = time.toInstant().toEpochMilli()
            if (at > now.toInstant().toEpochMilli() && at <= limit) result.add(Reminder(id, at, title, body, page, expiresAt))
        }
        if (options.classes && table?.kind == TableKind.STUDENT) {
            val monday = LocalDate.parse(table.monday)
            (0..7).forEach { offset ->
                val day = now.toLocalDate().plusDays(offset.toLong())
                val week = Math.floorDiv(ChronoUnit.DAYS.between(monday, day), 7).toInt() + 1
                if (week in 1..30) blocksFor(table.courses, week).filter { it.day == day.dayOfWeek.value }.forEach { block ->
                    val range = Periods.range(block)
                    val start = LocalTime.parse(range.substringBefore('–'))
                    val end = LocalTime.parse(range.substringAfter('–'))
                    val endsAt = day.atTime(end).atZone(SchoolZone).toInstant().toEpochMilli()
                    add("class|${table.semester.id}|${block.id}|$day", day.atTime(start).atZone(SchoolZone).minusMinutes(options.classLead.coerceIn(0, 180).toLong()), "下一节课：${block.name}", "$range · ${block.room}", "timetable", endsAt)
                }
            }
        }
        if (options.exams) exams.forEach { exam ->
            val anchor = table?.monday?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: now.toLocalDate()
            val date = exam.date(anchor) ?: return@forEach
            val detail = listOf(exam.time, exam.room, exam.seat.takeIf { it.isNotBlank() }?.let { "座位 $it" }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
            if (options.eve) add("${exam.id}|eve", date.minusDays(1).atStartOfDay(SchoolZone).plusMinutes(options.eveMinutes.coerceIn(0, 1439).toLong()), "明天考试：${exam.course}", detail, "exams")
            if (options.morning) add("${exam.id}|morning", date.atStartOfDay(SchoolZone).plusMinutes(options.morningMinutes.coerceIn(0, 1439).toLong()), "今天考试：${exam.course}", detail, "exams")
            if (options.before) {
                Regex("""(\d{1,2})[:：](\d{2})""").find(exam.time)?.let { clock ->
                    runCatching { LocalTime.of(clock.groupValues[1].toInt(), clock.groupValues[2].toInt()) }.getOrNull()?.let { time ->
                        add("${exam.id}|before", date.atTime(time).atZone(SchoolZone).minusMinutes(options.examLead.coerceIn(0, 180).toLong()), "快考试了：${exam.course}", detail, "exams")
                    }
                }
            }
        }
        return result.distinctBy { it.id }.sortedBy { it.at }.take(200)
    }
}
