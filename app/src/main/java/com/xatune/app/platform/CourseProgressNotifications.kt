package com.xatune.app.platform

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.xatune.app.R
import com.xatune.app.model.CourseProgressPlanner
import com.xatune.app.model.CourseProgressStage
import com.xatune.app.model.Timetable
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal object CourseProgressNotifications {
    private const val CHANNEL = "course_progress"
    private const val NOTIFICATION_ID = 1
    private const val BOUNDARY = "com.xatune.app.COURSE_PROGRESS_BOUNDARY"
    private const val REFRESH = "com.xatune.app.COURSE_PROGRESS_REFRESH"
    private const val DISMISS = "com.xatune.app.COURSE_PROGRESS_DISMISS"

    fun synchronize(context: Context, account: String, table: Timetable?, enabled: Boolean, action: Intent? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val boundaryAlarm = broadcast(context, BOUNDARY)
        val refreshAlarm = broadcast(context, REFRESH)
        val dismissals = context.getSharedPreferences("course_progress_dismissals", Context.MODE_PRIVATE)
        if (!enabled || account.isBlank()) dismissals.edit().clear().apply()
        if (!enabled || account.isBlank() || !SystemCoordinator.notificationsAllowed(context) ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            alarms.cancel(boundaryAlarm)
            alarms.cancel(refreshAlarm)
            manager.cancel(CHANNEL, NOTIFICATION_ID)
            return
        }

        val now = Instant.now()
        if (action?.action == DISMISS && action.getStringExtra("account") == account) {
            val lesson = action.data?.lastPathSegment
            val end = action.getLongExtra("end", 0)
            if (lesson != null && end > now.toEpochMilli()) {
                val edit = dismissals.edit()
                dismissals.all.filterValues { it is Long && it <= now.toEpochMilli() }.keys.forEach(edit::remove)
                edit.putLong(lesson, end).apply()
            }
        }

        val plan = CourseProgressPlanner.plan(table?.takeIf { it.account == account }, now)
        val stage = plan.stage?.takeUnless { dismissals.getLong(it.lesson.id, 0) > now.toEpochMilli() }
        val boundary = plan.nextBoundary
        val exact = SystemCoordinator.exactAllowed(context)
        schedule(alarms, boundaryAlarm, boundary, exact, wakeup = true)
        if (stage == null || boundary == null) {
            alarms.cancel(refreshAlarm)
            manager.cancel(CHANNEL, NOTIFICATION_ID)
            return
        }

        // The system renders the countdown. Only the progress bar needs periodic updates while awake.
        val nextMinute = Instant.ofEpochSecond((now.epochSecond / 60 + 1) * 60)
        schedule(alarms, refreshAlarm, nextMinute.takeIf { it < boundary }, exact, wakeup = false)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "课程进度", NotificationManager.IMPORTANCE_LOW))
        post(context, manager, account, stage, now, boundary)
    }

    private fun post(context: Context, manager: NotificationManager, account: String, stage: CourseProgressStage, now: Instant, boundary: Instant) {
        val lesson = stage.lesson
        val progress = stage.percentAt(now)
        val timing = if (stage.preparing) "距上课" else "距下课"
        val room = stage.previousRoom?.let { "$it → ${lesson.room}" } ?: lesson.room
        val launch = SystemCoordinator.launchIntent(context, "timetable").setData(Uri.parse("xatu://course-progress"))
        val open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = broadcast(context, DISMISS) {
            data = Uri.Builder().scheme("xatu").authority("course-progress").appendPath(lesson.id).build()
            putExtra("account", account)
            putExtra("end", lesson.end.toEpochMilli())
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lesson.name)
            .setContentText("$timing · $room")
            .setContentIntent(open)
            .setDeleteIntent(dismiss)
            .addAction(0, "本节停止显示", dismiss)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(stage.until.toEpochMilli())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(boundary.toEpochMilli() - now.toEpochMilli())

        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(NotificationCompat.ProgressStyle().setProgress(progress))
                .setRequestPromotedOngoing(true)
        } else {
            builder.setProgress(100, progress, false)
        }
        try {
            manager.notify(CHANNEL, NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // The system can revoke notification permission between the check and the post.
        }
    }

    private fun schedule(alarms: AlarmManager, pending: PendingIntent, at: Instant?, exact: Boolean, wakeup: Boolean) {
        if (at == null) {
            alarms.cancel(pending)
            return
        }
        val time = at.toEpochMilli()
        if (exact) {
            try {
                if (wakeup) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
                else alarms.setExact(AlarmManager.RTC, time, pending)
                return
            } catch (_: SecurityException) {
                // Exact alarm access can change while a schedule is being rebuilt.
            }
        }
        if (wakeup) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        else alarms.set(AlarmManager.RTC, time, pending)
    }

    private fun broadcast(context: Context, action: String, configure: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, CourseProgressReceiver::class.java).setAction(action).apply(configure)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class CourseProgressReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SystemCoordinator.synchronizeCourseProgress(context, intent)
            } finally {
                result.finish()
            }
        }
    }
}
