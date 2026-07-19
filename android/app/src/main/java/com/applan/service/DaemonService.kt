package com.applan.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.content.ComponentName
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
import com.applan.util.AppConfig
import com.applan.util.AppState

/**
 * 守护服务（Daemon Service）
 * 第二个前台服务，与KeepAliveService互相守护
 * Cactus式双进程保活：两个Service都在onDestroy中重启对方
 */
class DaemonService : Service() {

    companion object {
        private const val TAG = "Daemon"
        private const val NOTIFICATION_ID = 10002
        private const val JOB_ID_DAEMON_RESTART = 20002

        fun start(context: Context) {
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

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var pingRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Daemon service created")

        handlerThread = HandlerThread("DaemonWorker").apply {
            start()
            handler = Handler(looper)
        }

        pingRunnable = object : Runnable {
            override fun run() {
                if (AppState.canStartKeepAlive()) {
                    KeepAliveService.start(this@DaemonService)
                }
                handler?.postDelayed(this, 20000)
            }
        }
        handler?.postDelayed(pingRunnable!!, 5000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (AppState.canStartKeepAlive()) {
            KeepAliveService.start(this)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved - restarting both services + showing overlay")
        try {
            if (!AppConfig.isExitGranted() && AppState.shouldBlock()) {
                Handler(android.os.Looper.getMainLooper()).post {
                    BlockOverlay.show(this)
                }
            }
        } catch (_: Exception) {}
        try {
            val restartService = Intent(this, DaemonService::class.java)
            ContextCompat.startForegroundService(this, restartService)
        } catch (_: Exception) {}
        scheduleRestart(300)
        scheduleJobRestart()
        if (AppState.canStartKeepAlive()) {
            KeepAliveService.start(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Daemon destroyed - restarting both services")
        pingRunnable?.let { handler?.removeCallbacks(it) }
        handlerThread?.quitSafely()
        scheduleRestart(300)
        scheduleJobRestart()
        if (AppState.canStartKeepAlive()) {
            KeepAliveService.start(this)
        }
    }

    private fun scheduleRestart(delayMs: Long = 300) {
        try {
            val restartIntent = Intent(this, DaemonService::class.java)
            val pi = PendingIntent.getService(
                this, 1002, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val trigger = SystemClock.elapsedRealtime() + delayMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Schedule restart failed", e)
            try { start(this) } catch (_: Exception) {}
        }
    }

    private fun scheduleJobRestart() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val js = getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
                val component = ComponentName(this, KeepAliveJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID_DAEMON_RESTART, component)
                    .setMinimumLatency(1000)
                    .setOverrideDeadline(3000)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                js.schedule(jobInfo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule job restart", e)
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
