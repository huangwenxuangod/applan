package com.lockai.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lockai.MainActivity

class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL_ID = "lockai_service"
        private const val NOTIFICATION_ID = 10001
        private const val ACTION_RESTART = "com.lockai.RESTART_SERVICE"
        private const val ACTION_PING = "com.lockai.PING"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF")
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON - launching LockAI")
                    launchMainActivity()
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User present (unlocked) - launching LockAI")
                    launchMainActivity()
                }
            }
        }
    }

    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESTART || intent.action == ACTION_PING) {
                Log.d(TAG, "Restart ping received, restarting self")
                start(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        // 注册屏幕开关广播
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        // 注册重启广播
        val restartFilter = IntentFilter().apply {
            addAction(ACTION_RESTART)
            addAction(ACTION_PING)
        }
        registerReceiver(restartReceiver, restartFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: action=${intent?.action}")
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 标记无障碍服务为未放行状态（新一轮守护）
        LockAccessibilityService.getInstance()?.setGranted(false)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉时触发（但excludeFromRecents=true时不会触发）
        // 保留作为兜底重启方案
        Log.w(TAG, "onTaskRemoved - scheduling restart via AlarmManager")
        scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Service onDestroy - scheduling restart")
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(restartReceiver) } catch (_: Exception) {}
        // 被杀时用AlarmManager重启自己
        scheduleRestart()
    }

    private fun scheduleRestart() {
        try {
            val restartIntent = Intent(this, KeepAliveService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 1001, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + 500
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt, pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt, pendingIntent
                )
            }
            Log.d(TAG, "Restart scheduled in 500ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart", e)
            // 最后兜底：直接重启
            try {
                val intent = Intent(this, KeepAliveService::class.java)
                ContextCompat.startForegroundService(this, intent)
            } catch (_: Exception) {}
        }
    }

    private fun launchMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "锁屏保护", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "LockAI正在守护你的注意力"
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LockAI 守护中")
            .setContentText("每一次解锁都是一次选择")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
