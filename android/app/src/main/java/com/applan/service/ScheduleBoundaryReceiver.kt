package com.applan.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.applan.util.PolicyRepository
import com.applan.util.AppConfig
import java.util.Calendar

class ScheduleBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val active = PolicyRepository(context).evaluate().isScheduled
        if (!active || (AppConfig.isExitGranted() && !AppConfig.isPlanModeEnabled())) {
            BlockOverlay.hide()
        }
        scheduleNext(context)
    }

    companion object {
        private const val REQUEST_CODE = 42001

        fun scheduleNext(context: Context) {
            val profiles = PolicyRepository(context).getProfiles()
            val now = Calendar.getInstance()
            val next = profiles.flatMap { profile ->
                listOf(profile.startMinute, profile.endMinute).map { minute ->
                    Calendar.getInstance().apply {
                        timeInMillis = now.timeInMillis
                        set(Calendar.HOUR_OF_DAY, minute / 60)
                        set(Calendar.MINUTE, minute % 60)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
                    }.timeInMillis
                }
            }.minOrNull()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, ScheduleBoundaryReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (next == null) {
                alarmManager.cancel(pendingIntent)
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pendingIntent)
            }
        }
    }
}
