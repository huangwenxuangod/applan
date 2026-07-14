package com.lockai.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lockai.CHANNEL_KEEP_ALIVE
import com.lockai.MainActivity
import com.lockai.R
import com.lockai.util.AppState

/**
 * 前台保活服务
 * 核心策略（Cactus式保活）：
 * 1. startForeground 常驻通知
 * 2. onTaskRemoved 立即拉起自己 + MainActivity
 * 3. onDestroy 用 AlarmManager 延迟重启
 * 4. 与 DaemonService 互相守护
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val NOTIFICATION_ID = 10001

        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "KeepAlive service created")
        // 每30秒检查守护状态
        checkRunnable = object : Runnable {
            override fun run() {
                // 检查DaemonService是否存活，不活就拉起来
                DaemonService.start(this@KeepAliveService)
                // 如果处于守护模式且App不在前台，拉起MainActivity
                if (AppState.shouldBlock()) {
                    try {
                        val intent = Intent(this@KeepAliveService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
                handler.postDelayed(this, 30000)
            }
        }
        handler.postDelayed(checkRunnable!!, 10000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // 同时启动守护服务
        DaemonService.start(this)
        // START_STICKY：被杀后系统会尝试重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务列表划掉App时触发
     * 核心狠招：立即拉起自己+MainActivity
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved - app was swiped away, restarting everything!")
        // 立即重启MainActivity
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart MainActivity on task removed", e)
        }
        // 立即重启自己
        try {
            val restartService = Intent(this, KeepAliveService::class.java)
            ContextCompat.startForegroundService(this, restartService)
        } catch (_: Exception) {}
        // AlarmManager兜底
        scheduleRestart(100)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "KeepAlive destroyed - scheduling restart")
        checkRunnable?.let { handler.removeCallbacks(it) }
        // 被销毁时立即重启
        scheduleRestart(500)
        // 立即启动DaemonService
        DaemonService.start(this)
    }

    /**
     * 用AlarmManager安排重启，即使App进程被杀也能拉起
     */
    private fun scheduleRestart(delayMs: Long = 300) {
        try {
            val restartIntent = Intent(this, KeepAliveService::class.java)
            val pi = PendingIntent.getService(
                this, 1001, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val trigger = SystemClock.elapsedRealtime() + delayMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
            Log.d(TAG, "Restart scheduled in ${delayMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Schedule restart failed", e)
            // 直接尝试启动
            try { start(this) } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.getNotificationChannel(CHANNEL_KEEP_ALIVE) ?: run {
                val channel = NotificationChannel(
                    CHANNEL_KEEP_ALIVE, "锁屏保护",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_KEEP_ALIVE)
            .setContentTitle("AppPlan 守护中")
            .setContentText("注意力防线激活中")
            .setSmallIcon(R.drawable.ic_lock)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()
    }
}
