package com.xatune.app.platform

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.xatune.app.*
import com.xatune.app.R
import com.xatune.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object SystemCoordinator {
    private val mutex = Mutex()
    fun exactAllowed(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun notificationsAllowed(context: Context) = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun alarmIntent(context: Context, id: String, reminder: Reminder? = null, account: String = ""): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction("com.xatune.app.REMINDER").setData(Uri.parse("xatu://reminder/${Uri.encode(id)}"))
        if (reminder != null) intent.putExtra("title", reminder.title).putExtra("body", reminder.body).putExtra("page", reminder.page).putExtra("account", account).putExtra("id", id)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    suspend fun synchronize(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        val app = context.applicationContext as HelperApplication
        val store = app.store
        val account = store.account()
        val semester = if (account.isNotBlank()) store.currentSemester(account) else ""
        val table = if (account.isNotBlank()) store.table(account, semester, TableKind.STUDENT) else null
        val exams = if (account.isNotBlank()) store.academic(account, AcademicKind.EXAMS, semester)?.exams.orEmpty() else emptyList()
        val prefs = context.getSharedPreferences("system_schedule", Context.MODE_PRIVATE)
        val manager = context.getSystemService(AlarmManager::class.java)
        prefs.getStringSet("ids", emptySet()).orEmpty().forEach { manager.cancel(alarmIntent(context, it)) }
        val settings = if (account.isNotBlank()) app.campusRepository.reminders(account) else ReminderSettings()
        val planned = if (account.isNotBlank() && notificationsAllowed(context)) ReminderPlanner.plan(table, exams, settings) else emptyList()
        // Ledger is committed before arming alarms so immediate receivers can validate their identity.
        prefs.edit().putStringSet("ids", planned.map { it.id }.toSet()).putInt("count", planned.size).commit()
        planned.forEach { reminder ->
            val pending = alarmIntent(context, reminder.id, reminder, account)
            try {
                if (exactAllowed(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, pending)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, pending)
            } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, pending) }
        }
        if (account.isBlank()) NotificationManagerCompat.from(context).cancelAll()
        CampusWidgets.publish(context, account, table)
    } }
    fun maintain(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("campus-maintenance", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS).build())
    }
    fun refreshElectricity(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("widget-electricity", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ElectricityWidgetWorker>().build())
    }
    fun launchIntent(context: Context, page: String): Intent = Intent(context, MainActivity::class.java).putExtra("open_page", page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as HelperApplication
                val id = intent.getStringExtra("id").orEmpty()
                val registered = context.getSharedPreferences("system_schedule", Context.MODE_PRIVATE).getStringSet("ids", emptySet()).orEmpty()
                if (intent.getStringExtra("account") != app.store.account() || id !in registered || !SystemCoordinator.notificationsAllowed(context)) return@launch
                val manager = context.getSystemService(NotificationManager::class.java)
                val channel = if (id.startsWith("class|")) "classes" else "exams"
                manager.createNotificationChannel(NotificationChannel(channel, if (channel == "classes") "上课提醒" else "考试提醒", NotificationManager.IMPORTANCE_HIGH))
                val launch = PendingIntent.getActivity(context, id.hashCode(), SystemCoordinator.launchIntent(context, intent.getStringExtra("page") ?: "timetable").setData(Uri.parse("xatu://notice/${Uri.encode(id)}")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(intent.getStringExtra("title")).setContentText(intent.getStringExtra("body")).setStyle(NotificationCompat.BigTextStyle().bigText(intent.getStringExtra("body"))).setContentIntent(launch).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                try { manager.notify(id, id.hashCode(), notification) } catch (_: SecurityException) { }
            } finally { pending.finish() }
        }
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { SystemCoordinator.synchronize(context); SystemCoordinator.maintain(context); UpdateScheduler.synchronize(context) } finally { result.finish() } }
    }
}
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HelperApplication
        SystemCoordinator.synchronize(applicationContext)
        val account = app.store.account()
        val campus = app.campusRepository
        if (account.isNotBlank() && campus.selectedDorm(account) != null && campus.autoElectricity(account)) {
            val date = campus.electricity(account)?.checkedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(SchoolZone).toLocalDate() }
            if (date != schoolToday()) runCatching { app.repository.initialize(); campus.refreshElectricity(account) }.onSuccess { SystemCoordinator.synchronize(applicationContext) }
        }
        return Result.success()
    }
}
class ElectricityWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HelperApplication
        val account = app.store.account()
        try {
            app.repository.initialize()
            if (account.isNotBlank()) app.campusRepository.refreshElectricity(account)
            applicationContext.getSharedPreferences("widget_options", Context.MODE_PRIVATE).edit().remove("electricError").apply()
        } catch (_: Exception) {
            applicationContext.getSharedPreferences("widget_options", Context.MODE_PRIVATE).edit().putString("electricError", "刷新未成功，请打开 App 重试").apply()
        }
        SystemCoordinator.synchronize(applicationContext)
        return Result.success()
    }
}
