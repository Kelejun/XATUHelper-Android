package com.xatune.app.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class WidgetDay(val date: String, val week: Int, val courses: List<CourseBlock>)
data class WidgetCourseSnapshot(
    val account: String,
    val source: String,
    val days: List<WidgetDay>,
    val colorIndices: Map<String, Int> = emptyMap()
) {
    fun day(date: LocalDate): WidgetDay? = days.firstOrNull { it.date == date.toString() }
    fun colorIndexFor(course: CourseBlock, paletteSize: Int = 12): Int {
        colorIndices[course.key]?.let { return Math.floorMod(it, paletteSize) }
        var hash = 5381
        for (ch in course.name) hash = hash * 33 + ch.code
        return Math.floorMod(hash, paletteSize)
    }
}

fun widgetSource(table: Timetable?): String = table?.let { "${it.account}|${it.semester.id}|${it.fetchedAt}|${it.monday}|${it.courses.hashCode()}" }.orEmpty()

fun widgetSnapshot(account: String, table: Timetable?, today: LocalDate): WidgetCourseSnapshot {
    if (account.isBlank() || table == null || table.account != account || table.kind != TableKind.STUDENT) {
        return WidgetCourseSnapshot(account, widgetSource(table), emptyList())
    }
    val paletteIndices = table.courses.map { it.key }.distinct().sorted()
        .mapIndexed { index, key -> key to index % 12 }.toMap()
    val days = (0L..13L).map { offset ->
        val day = today.plusDays(offset)
        WidgetDay(day.toString(), weekOf(LocalDate.parse(table.monday), day), coursesOn(table, day))
    }
    return WidgetCourseSnapshot(account, widgetSource(table), days, paletteIndices)
}

fun weekdayLabel(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

data class WidgetCourseItem(
    val startTime: String,
    val endTime: String,
    val name: String,
    val room: String,
    val colorIndex: Int,
    val ongoing: Boolean
)

data class WidgetCourseCard(
    val header: String,
    val modeLabel: String,
    val showingTomorrow: Boolean,
    val autoTomorrow: Boolean,
    val featured: WidgetCourseItem?,
    val statusCompact: String,
    val statusWide: String,
    val upcoming: List<WidgetCourseItem>,
    val remainingCount: Int,
    val emptyTitle: String,
    val emptySubtitle: String
)

fun formatCourseWidget(snapshot: WidgetCourseSnapshot, now: ZonedDateTime, autoTomorrow: Boolean): WidgetCourseCard {
    val todayDate = now.toLocalDate()
    val todayDay = snapshot.day(todayDate)
    if (todayDay == null) {
        return WidgetCourseCard(
            header = weekdayLabel(todayDate.dayOfWeek),
            modeLabel = "今日课程",
            showingTomorrow = false,
            autoTomorrow = autoTomorrow,
            featured = null,
            statusCompact = "",
            statusWide = "",
            upcoming = emptyList(),
            remainingCount = 0,
            emptyTitle = "请先同步当前学期的学生课表",
            emptySubtitle = "点击打开课程表"
        )
    }
    val nowTime = now.toLocalTime()
    val todayRemaining = todayDay.courses.filter { block ->
        val end = runCatching { LocalTime.parse(Periods.range(block).substringAfter('–')) }.getOrNull()
        end == null || end > nowTime
    }
    val showingTomorrow = autoTomorrow && todayRemaining.isEmpty()
    val tomorrowDate = todayDate.plusDays(1)
    val displayed = if (showingTomorrow) snapshot.day(tomorrowDate)?.courses.orEmpty() else todayRemaining
    val header = if (showingTomorrow) {
        "${weekdayLabel(tomorrowDate.dayOfWeek)} · 明天"
    } else if (todayDay.week > 0) {
        "${weekdayLabel(todayDate.dayOfWeek)} · 第 ${todayDay.week} 周"
    } else {
        weekdayLabel(todayDate.dayOfWeek)
    }
    val items = displayed.map { block ->
        val range = Periods.range(block)
        val start = range.substringBefore('–')
        val end = range.substringAfter('–', start)
        val startTime = runCatching { LocalTime.parse(start) }.getOrNull()
        val ongoing = !showingTomorrow && startTime != null && !nowTime.isBefore(startTime)
        WidgetCourseItem(
            startTime = start,
            endTime = end,
            name = block.name,
            room = block.room.trim(),
            colorIndex = snapshot.colorIndexFor(block),
            ongoing = ongoing
        )
    }
    val featured = items.firstOrNull()
    val statusCompact = featured?.let { if (it.ongoing) "正在上课" else "${it.endTime} 下课" }.orEmpty()
    val statusWide = featured?.let {
        if (it.ongoing) "正在上课 · ${it.endTime} 下课" else "下一节 · ${it.endTime} 下课"
    }.orEmpty()
    val emptyTitle = when {
        showingTomorrow -> "明天没课"
        todayDay.courses.isEmpty() -> "今天没课"
        else -> "今天的课上完了"
    }
    return WidgetCourseCard(
        header = header,
        modeLabel = if (showingTomorrow) "明日课程" else "今日课程",
        showingTomorrow = showingTomorrow,
        autoTomorrow = autoTomorrow,
        featured = featured,
        statusCompact = statusCompact,
        statusWide = statusWide,
        upcoming = items.drop(1).take(2),
        remainingCount = items.size,
        emptyTitle = emptyTitle,
        emptySubtitle = "留点时间给自己"
    )
}

data class WidgetHolidayCard(
    val daysValue: String?,
    val showDaysUnit: Boolean,
    val title: String,
    val footer: String,
    val isEmpty: Boolean
)

fun formatHolidayWidget(calendar: SchoolCalendar?, today: LocalDate): WidgetHolidayCard {
    val next = nextCampusEvent(calendar, today)
    val active = nextBreak(calendar, today)?.takeIf { it.start <= today }
    if (next != null) {
        val days = ChronoUnit.DAYS.between(today, next.date).coerceAtLeast(0L)
        val dateText = "${next.date.monthValue}月${next.date.dayOfMonth}日 ${weekdayLabel(next.date.dayOfWeek)}"
        return WidgetHolidayCard(
            daysValue = if (days <= 0L) "今天" else days.toString(),
            showDaysUnit = days > 0L,
            title = next.title,
            footer = dateText,
            isEmpty = false
        )
    }
    if (active != null) {
        return WidgetHolidayCard(
            daysValue = "假期中",
            showDaysUnit = false,
            title = active.title,
            footer = active.end?.let { "至 ${it.monthValue}月${it.dayOfMonth}日" } ?: "校历识别供参考",
            isEmpty = false
        )
    }
    return WidgetHolidayCard(
        daysValue = null,
        showDaysUnit = false,
        title = "期待下一次放假",
        footer = "打开 App 查看校历后显示",
        isEmpty = true
    )
}

data class WidgetElectricityCard(
    val headerTitle: String,
    val balanceText: String,
    val unit: String,
    val caption: String,
    val isLow: Boolean,
    val roomName: String?,
    val updatedText: String,
    val hasBalance: Boolean
)

fun formatElectricityWidget(
    electric: Electricity?,
    dorm: Dorm?,
    now: ZonedDateTime,
    error: String? = null
): WidgetElectricityCard {
    val targetDorm = electric?.dorm ?: dorm
    val isAmount = targetDorm?.isAmountBalance == true
    val unit = targetDorm?.balanceUnit ?: "度"
    val headerTitle = if (isAmount) "宿舍电费" else "宿舍电量"
    val roomName = targetDorm?.let { d ->
        val building = d.building.name.trim()
        val room = d.room.name.trim()
        when {
            building.isEmpty() -> room
            room.isEmpty() -> building
            room.contains(building) -> room
            else -> "$building $room"
        }.takeIf { it.isNotEmpty() }
    }
    if (electric == null) {
        return WidgetElectricityCard(
            headerTitle = headerTitle,
            balanceText = "待查询",
            unit = unit,
            caption = if (dorm != null) (if (isAmount) "点击右侧刷新查询电费" else "点击右侧刷新查询电量") else "打开 App 选择宿舍",
            isLow = false,
            roomName = roomName,
            updatedText = error ?: if (dorm != null) "尚无查询记录" else "点击绑定宿舍",
            hasBalance = false
        )
    }
    val numeric = electric.balance.trim().toDoubleOrNull()
    val isLow = numeric != null && numeric < 10.0
    val formattedBalance = numeric?.let { String.format(Locale.US, "%.2f", it) } ?: electric.balance.trim()
    val checkedZoned = Instant.ofEpochMilli(electric.checkedAt).atZone(SchoolZone)
    val timePattern = if (checkedZoned.toLocalDate() == now.toLocalDate()) "HH:mm 更新" else "M/d HH:mm 更新"
    val updatedText = error ?: checkedZoned.format(DateTimeFormatter.ofPattern(timePattern))
    return WidgetElectricityCard(
        headerTitle = headerTitle,
        balanceText = formattedBalance,
        unit = unit,
        caption = when {
            isLow && isAmount -> "余额偏低，记得充值"
            isLow -> "电量偏低，记得充值"
            isAmount -> "剩余可用金额"
            else -> "剩余可用电量"
        },
        isLow = isLow,
        roomName = roomName,
        updatedText = updatedText,
        hasBalance = true
    )
}

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
    val displaySeat: String? get() = seat?.trim()?.takeIf { it.isNotEmpty() }?.let { "座位 $it" }
    val displayDateTime: String get() {
        val dateLabel = localDate?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: date
        return listOfNotNull(dateLabel, startTime).joinToString(" · ")
    }
    val displayVenue: String get() = listOfNotNull(displayRoom, displaySeat).joinToString(" ")
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
        val start = exam.startTime()
        val end = exam.endTime()
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

data class WidgetExamCard(
    val daysValue: String?,
    val showDaysUnit: Boolean,
    val primary: WidgetExamEntry?,
    val following: List<WidgetExamEntry>,
    val emptyTitle: String,
    val emptySubtitle: String
)

fun formatExamWidgetCard(snapshot: WidgetExamSnapshot?, now: ZonedDateTime): WidgetExamCard {
    val upcoming = snapshot?.upcoming(now).orEmpty()
    val first = upcoming.firstOrNull()
    if (first == null) {
        val emptySubtitle = if (snapshot == null || snapshot.fetchedAt == 0L) "点击同步当前学期考试" else "打开 App 查看考试安排"
        return WidgetExamCard(
            daysValue = null,
            showDaysUnit = false,
            primary = null,
            following = emptyList(),
            emptyTitle = "暂无待考科目",
            emptySubtitle = emptySubtitle
        )
    }
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), first.localDate!!).coerceAtLeast(0L)
    return WidgetExamCard(
        daysValue = if (days <= 0L) "今天" else days.toString(),
        showDaysUnit = days > 0L,
        primary = first,
        following = upcoming.drop(1).take(2),
        emptyTitle = "",
        emptySubtitle = ""
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
