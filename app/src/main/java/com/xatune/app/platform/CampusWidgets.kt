package com.xatune.app.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.xatune.app.HelperApplication
import com.xatune.app.R
import com.xatune.app.model.*
import com.xatune.app.ui.CoursePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime

object CampusWidgets {
    private const val WIDE_THRESHOLD_DP = 240

    suspend fun publish(
        context: Context,
        account: String,
        table: Timetable?,
        now: ZonedDateTime = ZonedDateTime.now(SchoolZone),
        scheduleRefresh: Boolean = true,
        semesterId: String = "",
        exams: AcademicSnapshot? = null
    ) {
        val app = context.applicationContext as HelperApplication
        val manager = AppWidgetManager.getInstance(context)
        val options = context.getSharedPreferences("widget_options", Context.MODE_PRIVATE)
        val previous = app.store.extra("widget.courseSnapshot", WidgetCourseSnapshot::class.java)
        val snapshot = if (previous?.account == account && previous.source == widgetSource(table)) {
            previous
        } else {
            widgetSnapshot(account, table, now.toLocalDate()).also { app.store.extra("widget.courseSnapshot", it) }
        }
        val anchor = table?.monday?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: now.toLocalDate()
        val previousExams = app.store.extra("widget.examSnapshot", WidgetExamSnapshot::class.java)
        val examSnapshot = if (account.isBlank()) {
            widgetExamSnapshot("", "", 0L, emptyList(), anchor).also { app.store.extra("widget.examSnapshot", it) }
        } else if (exams != null) {
            widgetExamSnapshot(account, semesterId, exams.fetchedAt, exams.exams, anchor).also { app.store.extra("widget.examSnapshot", it) }
        } else if (previousExams != null && previousExams.account == account && (semesterId.isBlank() || previousExams.semesterId == semesterId)) {
            previousExams
        } else {
            widgetExamSnapshot(account, semesterId, 0L, emptyList(), anchor).also { app.store.extra("widget.examSnapshot", it) }
        }
        if (scheduleRefresh) schedule(context, snapshot, examSnapshot, now)

        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val providers = listOf(
            "course" to CourseWidget::class.java,
            "electricity" to ElectricityWidget::class.java,
            "holiday" to HolidayWidget::class.java,
            "exam" to ExamWidget::class.java
        )
        for ((type, provider) in providers) {
            val page = when (type) {
                "course" -> "timetable"
                "electricity" -> "electricity"
                "exam" -> "exams"
                else -> "calendar"
            }
            val backgroundRes = when (type) {
                "course" -> R.drawable.widget_bg_course
                "electricity" -> R.drawable.widget_bg_electricity
                "exam" -> R.drawable.widget_bg_exam
                else -> R.drawable.widget_bg_holiday
            }
            for (id in manager.getAppWidgetIds(ComponentName(context, provider))) {
                val open = PendingIntent.getActivity(
                    context,
                    id,
                    SystemCoordinator.launchIntent(context, page).setData(Uri.parse("xatu://widget/$id")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                if (account.isBlank()) {
                    val loggedOut = RemoteViews(context.packageName, R.layout.widget_logged_out).apply {
                        setInt(R.id.widget_root, "setBackgroundResource", backgroundRes)
                        setOnClickPendingIntent(R.id.widget_root, open)
                    }
                    manager.updateAppWidget(id, loggedOut)
                    continue
                }
                val minWidth = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                val views = when (type) {
                    "course" -> {
                        val auto = options.getBoolean("tomorrow:$id", false)
                        val card = formatCourseWidget(snapshot, now, auto)
                        val toggle = PendingIntent.getBroadcast(
                            context,
                            id,
                            Intent(context, provider).setAction("com.xatune.app.TOGGLE_TOMORROW")
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val small = buildCourseSmall(context, card, dark, open, toggle)
                        val wide = buildCourseWide(context, card, dark, open, toggle)
                        responsiveViews(small, wide, minWidth)
                    }
                    "electricity" -> {
                        val card = formatElectricityWidget(
                            electric = app.campusRepository.electricity(account),
                            dorm = app.campusRepository.selectedDorm(account),
                            now = now,
                            error = options.getString("electricError", null)
                        )
                        val refresh = PendingIntent.getBroadcast(
                            context,
                            id,
                            Intent(context, provider).setAction("com.xatune.app.REFRESH_ELECTRICITY")
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        buildElectricity(context, card, open, refresh)
                    }
                    "holiday" -> {
                        val card = formatHolidayWidget(app.campusRepository.calendar(), now.toLocalDate())
                        buildHoliday(context, card, open)
                    }
                    else -> {
                        val card = formatExamWidgetCard(examSnapshot, now)
                        val small = buildExamSmall(context, card, open)
                        val wide = buildExamWide(context, card, open)
                        responsiveViews(small, wide, minWidth)
                    }
                }
                manager.updateAppWidget(id, views)
            }
        }
    }

    private fun responsiveViews(small: RemoteViews, wide: RemoteViews, minWidthDp: Int): RemoteViews {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(
                mapOf(
                    SizeF(140f, 140f) to small,
                    SizeF(WIDE_THRESHOLD_DP.toFloat(), 140f) to wide
                )
            )
        } else {
            if (minWidthDp >= WIDE_THRESHOLD_DP) wide else small
        }
    }

    private fun courseAccentColor(index: Int, dark: Boolean): Int {
        val palette = if (dark) CoursePalette.dark else CoursePalette.light
        return palette[Math.floorMod(index, palette.size)].accent.toArgb()
    }

    private fun bindCourseToggle(context: Context, views: RemoteViews, autoTomorrow: Boolean, toggle: PendingIntent) {
        views.setTextViewText(R.id.widget_action, if (autoTomorrow) "明✓" else "明日")
        views.setInt(
            R.id.widget_action,
            "setBackgroundResource",
            if (autoTomorrow) R.drawable.widget_chip_active else R.drawable.widget_chip_tonal
        )
        views.setTextColor(
            R.id.widget_action,
            ContextCompat.getColor(
                context,
                if (autoTomorrow) R.color.widget_accent_course else R.color.widget_text_secondary
            )
        )
        views.setOnClickPendingIntent(R.id.widget_action, toggle)
    }

    private fun buildCourseSmall(
        context: Context,
        card: WidgetCourseCard,
        dark: Boolean,
        open: PendingIntent,
        toggle: PendingIntent
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_course_small).apply {
        setOnClickPendingIntent(R.id.widget_root, open)
        setTextViewText(R.id.widget_header, card.header)
        bindCourseToggle(context, this, card.autoTomorrow, toggle)
        val featured = card.featured
        if (featured != null) {
            val accent = courseAccentColor(featured.colorIndex, dark)
            setViewVisibility(R.id.widget_content_group, View.VISIBLE)
            setViewVisibility(R.id.widget_empty_group, View.GONE)
            setViewVisibility(R.id.widget_footer_row, View.VISIBLE)

            setTextViewText(R.id.widget_course_time, featured.startTime)
            setTextColor(R.id.widget_course_time, accent)
            setTextViewText(R.id.widget_body, featured.name)
            if (featured.room.isNotEmpty()) {
                setViewVisibility(R.id.widget_room_row, View.VISIBLE)
                setTextViewText(R.id.widget_course_room, featured.room)
            } else {
                setViewVisibility(R.id.widget_room_row, View.GONE)
            }
            setInt(R.id.widget_status_dot, "setColorFilter", accent)
            setTextViewText(R.id.widget_status_text, card.statusCompact)
            if (card.remainingCount > 1) {
                setViewVisibility(R.id.widget_more_count, View.VISIBLE)
                setTextViewText(R.id.widget_more_count, "另有 ${card.remainingCount - 1} 节")
            } else {
                setViewVisibility(R.id.widget_more_count, View.GONE)
            }
        } else {
            setViewVisibility(R.id.widget_content_group, View.GONE)
            setViewVisibility(R.id.widget_empty_group, View.VISIBLE)
            setViewVisibility(R.id.widget_footer_row, View.GONE)
            setTextViewText(R.id.widget_body, card.emptyTitle)
            setTextViewText(R.id.widget_empty_title, card.emptyTitle)
            setTextViewText(R.id.widget_empty_subtitle, card.emptySubtitle)
        }
    }

    private fun buildCourseWide(
        context: Context,
        card: WidgetCourseCard,
        dark: Boolean,
        open: PendingIntent,
        toggle: PendingIntent
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_course_wide).apply {
        setOnClickPendingIntent(R.id.widget_root, open)
        setTextViewText(R.id.widget_header, card.header)
        setTextViewText(R.id.widget_header_trailing, card.modeLabel)
        bindCourseToggle(context, this, card.autoTomorrow, toggle)
        val featured = card.featured
        if (featured != null) {
            val accent = courseAccentColor(featured.colorIndex, dark)
            setViewVisibility(R.id.widget_content_group, View.VISIBLE)
            setViewVisibility(R.id.widget_empty_group, View.GONE)

            setTextViewText(R.id.widget_course_time, featured.startTime)
            setTextColor(R.id.widget_course_time, accent)
            setTextViewText(R.id.widget_body, featured.name)
            if (featured.room.isNotEmpty()) {
                setViewVisibility(R.id.widget_room_row, View.VISIBLE)
                setTextViewText(R.id.widget_course_room, featured.room)
            } else {
                setViewVisibility(R.id.widget_room_row, View.GONE)
            }
            setTextViewText(R.id.widget_status_text, card.statusWide)
            setTextColor(R.id.widget_status_text, accent)

            val firstNext = card.upcoming.getOrNull(0)
            val secondNext = card.upcoming.getOrNull(1)
            if (firstNext != null) {
                setViewVisibility(R.id.widget_no_more, View.GONE)
                setViewVisibility(R.id.widget_next_1, View.VISIBLE)
                val c1 = courseAccentColor(firstNext.colorIndex, dark)
                setInt(R.id.widget_next_1_bar, "setColorFilter", c1)
                setTextViewText(R.id.widget_next_1_time, firstNext.startTime)
                setTextColor(R.id.widget_next_1_time, c1)
                setTextViewText(R.id.widget_next_1_room, firstNext.room)
                setTextViewText(R.id.widget_next_1_name, firstNext.name)
            } else {
                setViewVisibility(R.id.widget_next_1, View.GONE)
                setViewVisibility(R.id.widget_no_more, View.VISIBLE)
            }
            if (secondNext != null) {
                setViewVisibility(R.id.widget_next_2, View.VISIBLE)
                val c2 = courseAccentColor(secondNext.colorIndex, dark)
                setInt(R.id.widget_next_2_bar, "setColorFilter", c2)
                setTextViewText(R.id.widget_next_2_time, secondNext.startTime)
                setTextColor(R.id.widget_next_2_time, c2)
                setTextViewText(R.id.widget_next_2_room, secondNext.room)
                setTextViewText(R.id.widget_next_2_name, secondNext.name)
            } else {
                setViewVisibility(R.id.widget_next_2, View.GONE)
            }
            if (card.remainingCount > 3) {
                setViewVisibility(R.id.widget_more_count, View.VISIBLE)
                setTextViewText(R.id.widget_more_count, "还有 ${card.remainingCount - 3} 节课")
            } else {
                setViewVisibility(R.id.widget_more_count, View.GONE)
            }
        } else {
            setViewVisibility(R.id.widget_content_group, View.GONE)
            setViewVisibility(R.id.widget_empty_group, View.VISIBLE)
            setTextViewText(R.id.widget_body, card.emptyTitle)
            setTextViewText(R.id.widget_empty_title, card.emptyTitle)
            setTextViewText(R.id.widget_empty_subtitle, card.emptySubtitle)
        }
    }

    private fun buildHoliday(context: Context, card: WidgetHolidayCard, open: PendingIntent): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_holiday).apply {
            setOnClickPendingIntent(R.id.widget_root, open)
            if (!card.isEmpty && card.daysValue != null) {
                setViewVisibility(R.id.widget_content_group, View.VISIBLE)
                setViewVisibility(R.id.widget_empty_group, View.GONE)
                setTextViewText(R.id.widget_days_value, card.daysValue)
                setTextViewTextSize(
                    R.id.widget_days_value,
                    TypedValue.COMPLEX_UNIT_SP,
                    if (card.showDaysUnit) 42f else 30f
                )
                setViewVisibility(R.id.widget_days_unit, if (card.showDaysUnit) View.VISIBLE else View.GONE)
                setTextViewText(R.id.widget_body, card.title)
            } else {
                setViewVisibility(R.id.widget_content_group, View.GONE)
                setViewVisibility(R.id.widget_empty_group, View.VISIBLE)
                setTextViewText(R.id.widget_body, card.title)
                setTextViewText(R.id.widget_empty_title, card.title)
            }
            setTextViewText(R.id.widget_footer, card.footer)
        }

    private fun buildElectricity(
        context: Context,
        card: WidgetElectricityCard,
        open: PendingIntent,
        refresh: PendingIntent
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_electricity).apply {
        setOnClickPendingIntent(R.id.widget_root, open)
        setOnClickPendingIntent(R.id.widget_action, refresh)
        setTextViewText(R.id.widget_header, card.headerTitle)
        setTextViewText(R.id.widget_body, card.balanceText)
        setTextViewTextSize(
            R.id.widget_body,
            TypedValue.COMPLEX_UNIT_SP,
            when {
                !card.hasBalance -> 26f
                card.balanceText.length >= 7 -> 24f
                else -> 30f
            }
        )
        setTextColor(
            R.id.widget_body,
            ContextCompat.getColor(
                context,
                if (card.isLow) R.color.widget_accent_electricity else R.color.widget_text
            )
        )
        if (card.hasBalance) {
            setViewVisibility(R.id.widget_balance_unit, View.VISIBLE)
            setTextViewText(R.id.widget_balance_unit, card.unit)
        } else {
            setViewVisibility(R.id.widget_balance_unit, View.GONE)
        }
        setTextViewText(R.id.widget_balance_caption, card.caption)
        setTextColor(
            R.id.widget_balance_caption,
            ContextCompat.getColor(
                context,
                if (card.isLow) R.color.widget_accent_electricity else R.color.widget_text_secondary
            )
        )
        if (!card.roomName.isNullOrBlank()) {
            setViewVisibility(R.id.widget_dorm_row, View.VISIBLE)
            setTextViewText(R.id.widget_dorm_name, card.roomName)
        } else {
            setViewVisibility(R.id.widget_dorm_row, View.GONE)
        }
        setTextViewText(R.id.widget_updated_at, card.updatedText)
    }

    private fun buildExamSmall(context: Context, card: WidgetExamCard, open: PendingIntent): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_exam_small).apply {
            setOnClickPendingIntent(R.id.widget_root, open)
            bindExamPrimaryColumn(this, card, showVenue = true)
        }

    private fun buildExamWide(context: Context, card: WidgetExamCard, open: PendingIntent): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_exam_wide).apply {
            setOnClickPendingIntent(R.id.widget_root, open)
            val primary = card.primary
            val hasFollowing = card.following.isNotEmpty()
            bindExamPrimaryColumn(this, card, showVenue = hasFollowing)
            if (primary == null) {
                setViewVisibility(R.id.widget_exam_divider, View.GONE)
                setViewVisibility(R.id.widget_exam_side, View.GONE)
            } else {
                setViewVisibility(R.id.widget_exam_divider, View.VISIBLE)
                setViewVisibility(R.id.widget_exam_side, View.VISIBLE)
                if (hasFollowing) {
                    setTextViewText(R.id.widget_side_title, "后续考试")
                    setViewVisibility(R.id.widget_single_exam_venue, View.GONE)
                    val first = card.following.getOrNull(0)
                    val second = card.following.getOrNull(1)
                    if (first != null) {
                        setViewVisibility(R.id.widget_next_exam_1, View.VISIBLE)
                        setTextViewText(R.id.widget_next_exam_1_time, first.displayDateTime)
                        setTextViewText(R.id.widget_next_exam_1_name, first.course)
                    } else {
                        setViewVisibility(R.id.widget_next_exam_1, View.GONE)
                    }
                    if (second != null) {
                        setViewVisibility(R.id.widget_next_exam_2, View.VISIBLE)
                        setTextViewText(R.id.widget_next_exam_2_time, second.displayDateTime)
                        setTextViewText(R.id.widget_next_exam_2_name, second.course)
                    } else {
                        setViewVisibility(R.id.widget_next_exam_2, View.GONE)
                    }
                } else {
                    setTextViewText(R.id.widget_side_title, "本场考试")
                    setViewVisibility(R.id.widget_next_exam_1, View.GONE)
                    setViewVisibility(R.id.widget_next_exam_2, View.GONE)
                    setViewVisibility(R.id.widget_single_exam_venue, View.VISIBLE)
                    setTextViewText(R.id.widget_single_room, primary.displayRoom)
                    val seat = primary.displaySeat
                    if (seat != null) {
                        setViewVisibility(R.id.widget_single_seat, View.VISIBLE)
                        setTextViewText(R.id.widget_single_seat, seat)
                    } else {
                        setViewVisibility(R.id.widget_single_seat, View.GONE)
                    }
                }
            }
        }

    private fun bindExamPrimaryColumn(views: RemoteViews, card: WidgetExamCard, showVenue: Boolean) {
        val primary = card.primary
        if (primary != null && card.daysValue != null) {
            views.setViewVisibility(R.id.widget_content_group, View.VISIBLE)
            views.setViewVisibility(R.id.widget_empty_group, View.GONE)
            views.setTextViewText(R.id.widget_days_value, card.daysValue)
            views.setTextViewTextSize(
                R.id.widget_days_value,
                TypedValue.COMPLEX_UNIT_SP,
                if (card.showDaysUnit) 36f else 30f
            )
            views.setViewVisibility(R.id.widget_days_unit, if (card.showDaysUnit) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_body, primary.course)
            views.setTextViewText(R.id.widget_exam_time, primary.displayDateTime)
            if (showVenue) {
                views.setViewVisibility(R.id.widget_exam_venue, View.VISIBLE)
                views.setTextViewText(R.id.widget_exam_venue, primary.displayVenue)
            } else {
                views.setViewVisibility(R.id.widget_exam_venue, View.GONE)
            }
        } else {
            views.setViewVisibility(R.id.widget_content_group, View.GONE)
            views.setViewVisibility(R.id.widget_empty_group, View.VISIBLE)
            views.setTextViewText(R.id.widget_body, card.emptyTitle)
            views.setTextViewText(R.id.widget_empty_title, card.emptyTitle)
            views.setTextViewText(R.id.widget_exam_time, card.emptySubtitle)
            views.setViewVisibility(R.id.widget_exam_venue, View.GONE)
        }
    }

    private fun schedule(context: Context, snapshot: WidgetCourseSnapshot, examSnapshot: WidgetExamSnapshot?, now: ZonedDateTime) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WidgetRefreshReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = nextWidgetRefresh(snapshot, now, examSnapshot)
        try {
            if (SystemCoordinator.exactAllowed(context)) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }
}

class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { SystemCoordinator.synchronize(context) } finally { result.finish() } }
    }
}

open class CampusWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetPinning.PINNED) {
            context.getSharedPreferences("widget_options", Context.MODE_PRIVATE).edit()
                .putLong(WidgetPinning.CONFIRMED, System.currentTimeMillis()).apply()
            update(context)
            return
        }
        if (intent.action == "com.xatune.app.REFRESH_ELECTRICITY") {
            SystemCoordinator.refreshElectricity(context)
            return
        }
        if (intent.action == "com.xatune.app.TOGGLE_TOMORROW") {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (id < 0) return
            val prefs = context.getSharedPreferences("widget_options", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("tomorrow:$id", !prefs.getBoolean("tomorrow:$id", false)).apply()
            update(context)
            return
        }
        super.onReceive(context, intent)
    }

    private fun update(context: Context) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { SystemCoordinator.synchronize(context) } finally { result.finish() } }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { update(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { update(context) }
    override fun onDeleted(context: Context, ids: IntArray) {
        context.getSharedPreferences("widget_options", Context.MODE_PRIVATE).edit().apply {
            ids.forEach { remove("tomorrow:$it") }
        }.apply()
    }
}

class CourseWidget : CampusWidgetProvider()
class ElectricityWidget : CampusWidgetProvider()
class HolidayWidget : CampusWidgetProvider()
class ExamWidget : CampusWidgetProvider()
