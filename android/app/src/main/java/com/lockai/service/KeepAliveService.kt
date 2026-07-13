package com.lockai.service

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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lockai.MainActivity
import com.lockai.R
import com.lockai.ui.markNeedsReset

class KeepAliveService : Service() {

    companion object {
        private const val TAG = "LockAIAlive"
        private const val CHANNEL_ID = "lockai_keepalive"
        private const val NOTIFICATION_ID = 10001

        private var isRunning = false

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
            }
        }

        fun isRunning(): Boolean = isRunning
    }

    // 屏幕解锁广播接收器
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            Log.d(TAG, "Screen unlocked, launching MainActivity")
            markNeedsReset() // 每次解锁都重置对话
            launchMainActivity(context)
        }
    }

    // 屏幕关闭广播
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            Log.d(TAG, "Screen off, marking reset for next unlock")
            markNeedsReset() // 锁屏时标记下次解锁重置
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // 注册解锁广播
        val unlockFilter = IntentFilter(Intent.ACTION_USER_PRESENT)
        registerReceiver(unlockReceiver, unlockFilter)

        // 注册屏幕关闭广播
        val screenOffFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, screenOffFilter)

        Log.d(TAG, "KeepAliveService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: 服务被杀死后系统会自动重启
        Log.d(TAG, "onStartCommand, returning START_STICKY")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉APP时，重启服务+启动Activity
        Log.d(TAG, "Task removed! Restarting service and activity...")
        val restartIntent = Intent(this, KeepAliveService::class.java)
        startService(restartIntent)
        launchMainActivity(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            unregisterReceiver(unlockReceiver)
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {}
        Log.d(TAG, "KeepAliveService destroyed, restarting...")
        // 服务被销毁时重启自己
        val restartIntent = Intent(this, KeepAliveService::class.java)
        startService(restartIntent)
    }

    private fun launchMainActivity(context: Context) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity", e)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LockAI 运行中")
            .setContentText("守护你的专注时间")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LockAI 后台服务",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持LockAI在后台运行，解锁时自动弹出"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
