package com.xatune.app.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xatune.app.HelperApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** Recompute the next local 09:00 after each alarm and after clock/time-zone changes. */
fun nextUpdateAt(now: ZonedDateTime): ZonedDateTime {
    val today = now.toLocalDate().atTime(9, 0).atZone(now.zone)
    return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(9, 0).atZone(now.zone)
}

object UpdateScheduler {
    private const val DAILY_WORK = "app-update-daily"
    private fun alarm(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, UpdateAlarmReceiver::class.java).setAction("com.xatune.app.CHECK_UPDATE"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    suspend fun synchronize(context: Context) {
        val updates = (context.applicationContext as HelperApplication).updates
        updates.initialize()
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = alarm(context)
        manager.cancel(pending)
        if (!updates.state.value.settings.automatic) {
            WorkManager.getInstance(context).cancelUniqueWork(DAILY_WORK)
            return
        }
        val at = nextUpdateAt(ZonedDateTime.now()).toInstant().toEpochMilli()
        try {
            if (SystemCoordinator.exactAllowed(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
    }

    fun checkDaily(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(DAILY_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateWorker>().build())
    }
}

class UpdateAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                UpdateScheduler.synchronize(context)
                UpdateScheduler.checkDaily(context)
            } finally { pending.finish() }
        }
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as HelperApplication).updates.check()
        return Result.success()
    }
}
