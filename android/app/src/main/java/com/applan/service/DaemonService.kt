package com.applan.service

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
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.applan.CHANNEL_KEEP_ALIVE
import com.applan.R
import com.applan.util.AppState

/**
 * 守护服务（Daemon Service）
 * 第二个前台服务，与KeepAliveService互相守护
 * Cactus式双进程保活：两个Service都在onDestroy中重启对方
 *
 * 性能优化：
 * - 使用工作线程Handler，不阻塞主线程
 * - Service启动冷却机制，防止双Service互相无限启动
 * - 检查间隔从15秒延长到30秒，减少唤醒
 */
class DaemonService : Service() {

    companion object {
        private const val TAG = "Daemon"
        private const val NOTIFICATION_ID = 10002
        private const val ACTION_PING = "com.applan.DAEMON_PING"

        fun start(context: Context) {
            // 性能优化：加启动冷却，防止短时间内重复启动
            if (!AppState.canStartDaemon()) {
                return
            }
            try {
                val intent = Intent(context, DaemonService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start daemon", e)
            }
        }
    }

    // 性能优化：使用工作线程Handler，不占用主线程
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var pingRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Daemon service created")

        // 创建工作线程，所有定时任务跑在工作线程，不阻塞UI
        handlerThread = HandlerThread("DaemonWorker").apply {
            start()
            handler = Handler(looper)
        }

        // 每20秒检查KeepAliveService是否存活（工作线程，不影响UI）
        pingRunnable = object : Runnable {
            override fun run() {
                // 加冷却判断，避免无限循环启动
                if (AppState.canStartKeepAlive()) {
                    KeepAliveService.start(this@DaemonService)
                }
                handler?.postDelayed(this, 20000) // 20秒检查一次
            }
        }
        handler?.postDelayed(pingRunnable!!, 5000) // 首次5秒后检查
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // 启动KeepAliveService时加冷却判断
        if (AppState.canStartKeepAlive()) {
            KeepAliveService.start(this)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved - restarting")
        scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Daemon destroyed - restarting both services")
        pingRunnable?.let { handler?.removeCallbacks(it) }
        handlerThread?.quitSafely()
        scheduleRestart()
        // 立即重启KeepAliveService（加冷却判断）
        if (AppState.canStartKeepAlive()) {
            KeepAliveService.start(this)
        }
    }

    private fun scheduleRestart() {
        try {
            val restartIntent = Intent(this, DaemonService::class.java)
            val pi = PendingIntent.getService(
                this, 1002, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val trigger = SystemClock.elapsedRealtime() + 300
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Schedule restart failed", e)
            // 直接重启
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
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_KEEP_ALIVE)
            .setContentTitle("applan 守护中")
            .setContentText("注意力防线激活")
            .setSmallIcon(R.drawable.ic_lock)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
