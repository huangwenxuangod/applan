package com.applan.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.applan.CHANNEL_KEEP_ALIVE
import com.applan.MainActivity
import com.applan.R
import com.applan.util.AppConfig
import com.applan.util.AppState
import com.applan.util.PermissionHelper

/**
 * 前台保活服务
 * 核心策略（Cactus式保活）：
 * 1. startForeground 常驻通知
 * 2. onTaskRemoved 立即显示遮罩 + 重启自己 + AlarmManager兜底 + JobScheduler兜底
 * 3. onDestroy 用 AlarmManager 延迟重启
 * 4. 与 DaemonService 互相守护
 *
 * 性能优化：
 * - 使用工作线程Handler，不阻塞主线程
 * - 只显示遮罩不自动拉起Activity，避免闪屏
 * - Service启动冷却机制，防止双Service互相无限启动
 * - 严格模式下监控关键权限，被关闭时立即拉起引导页
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val NOTIFICATION_ID = 10001
        private const val JOB_ID_RESTART = 20001

        // 静态引用mainHandler，供bringToForeground静态方法使用
        @Volatile
        private var mainHandlerInstance: Handler? = null

        fun start(context: Context) {
            if (!AppState.canStartKeepAlive()) {
                return
            }
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start", e)
            }
        }

        /**
         * 将App带回前台（用于检测到违规/AI工具调用lock_screen时）
         * 策略：先显示遮罩（毫秒级），再启动Activity（需要系统调度可能有延迟）
         */
        fun bringToForeground(context: Context, reason: String? = null) {
            Log.d(TAG, "bringToForeground: reason=$reason")
            try {
                // 1. 先显示遮罩（立即生效，不依赖Activity启动）
                mainHandlerInstance?.post {
                    try {
                        if (!BlockOverlay.isShowing()) {
                            BlockOverlay.show(context)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to show overlay in bringToForeground", e)
                    }
                } ?: run {
                    // Service可能还没创建mainHandler，直接尝试显示
                    try {
                        Handler(Looper.getMainLooper()).post {
                            BlockOverlay.show(context)
                        }
                    } catch (_: Exception) {}
                }

                // 2. 启动MainActivity
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    if (reason != null) {
                        putExtra("violation_reason", reason)
                    }
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bring to foreground", e)
            }
        }
    }

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var checkRunnable: Runnable? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "KeepAlive service created")
        mainHandlerInstance = mainHandler

        handlerThread = HandlerThread("KeepAliveWorker").apply {
            start()
            handler = Handler(looper)
        }

        checkRunnable = object : Runnable {
            override fun run() {
                var nextInterval = 15000L
                try {
                    if (AppConfig.isExitGranted()) {
                        if (AppState.canStartDaemon()) {
                            DaemonService.start(this@KeepAliveService)
                        }
                        nextInterval = 60000L
                    } else {
                        if (AppConfig.isStrictModeEnabled()) {
                            checkCriticalPermissions()
                        }

                        if (AppState.canStartDaemon()) {
                            DaemonService.start(this@KeepAliveService)
                        }

                        // 只显示遮罩，不自动拉起Activity（避免闪屏）
                        // 用户通过点击遮罩上的"返回applan"按钮回来
                        if (!AppConfig.isExitGranted() && AppState.shouldBlock() && !AppState.isActivityInForeground) {
                            if (!BlockOverlay.isShowing() && !BlockOverlay.wasJustShown(2000)) {
                                mainHandler.post { BlockOverlay.show(this@KeepAliveService) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Check runnable error", e)
                }

                handler?.postDelayed(this, nextInterval)
            }
        }
        handler?.post(checkRunnable!!)
        registerScreenReceiver()
    }

    private fun checkCriticalPermissions() {
        try {
            val a11yEnabled = PermissionHelper.isAccessibilityEnabled(this)
            val overlayEnabled = PermissionHelper.canDrawOverlays(this)

            if (!a11yEnabled || !overlayEnabled) {
                Log.w(TAG, "Strict mode: critical permission revoked! a11y=$a11yEnabled, overlay=$overlayEnabled")
                AppState.setPermissionRevoked(true)
                // 权限被关闭：必须立即拉起MainActivity（不能只显示遮罩，因为用户需要重新授权）
                forceLaunchMainActivity()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check permissions in strict mode", e)
        }
    }

    /**
     * 只显示遮罩，不自动启动Activity（避免闪屏）
     */
    private fun showBlockOverlay() {
        if (AppState.shouldBlock() && !AppState.isActivityInForeground && !BlockOverlay.isShowing()) {
            mainHandler.post { BlockOverlay.show(this@KeepAliveService) }
        }
    }

    /**
     * 强制启动MainActivity（仅用于权限撤销等必须拉起的场景）
     */
    private fun forceLaunchMainActivity() {
        try {
            mainHandler.post { BlockOverlay.show(this@KeepAliveService) }
            val intent = Intent(this@KeepAliveService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("permission_revoked", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force launch activity", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (AppState.canStartDaemon()) {
            DaemonService.start(this)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved - app was swiped away, restarting!")
        try {
            // 立即显示遮罩（遮罩在Service中，Service没死遮罩就能显示）
            if (!AppConfig.isExitGranted() && AppState.shouldBlock()) {
                mainHandler.post { BlockOverlay.show(this) }
            }
        } catch (_: Exception) {}
        try {
            val restartService = Intent(this, KeepAliveService::class.java)
            ContextCompat.startForegroundService(this, restartService)
        } catch (_: Exception) {}
        scheduleRestart(100)
        // JobScheduler兜底（国产ROM杀进程组后AlarmManager可能失效）
        scheduleJobRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "KeepAlive destroyed - scheduling restart")
        checkRunnable?.let { handler?.removeCallbacks(it) }
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            screenReceiver = null
        }
        handlerThread?.quitSafely()
        scheduleRestart(500)
        scheduleJobRestart()
        if (AppState.canStartDaemon()) {
            DaemonService.start(this)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                Log.d(TAG, "Screen event: $action")
                if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                    handler?.postDelayed({
                        if (!AppConfig.isExitGranted() && AppState.shouldBlock() && !AppState.isActivityInForeground) {
                            if (!BlockOverlay.isShowing() && !BlockOverlay.wasJustShown(2000)) {
                                mainHandler.post { BlockOverlay.show(context) }
                            }
                        }
                    }, 200)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            registerReceiver(screenReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen receiver", e)
        }
    }

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
        } catch (e: Exception) {
            Log.e(TAG, "Schedule restart failed", e)
            try { start(this) } catch (_: Exception) {}
        }
    }

    /**
     * JobScheduler兜底重启 - 国产ROM杀进程组后AlarmManager可能失效，JobScheduler更可靠
     */
    private fun scheduleJobRestart() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val js = getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
                val component = ComponentName(this, KeepAliveJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID_RESTART, component)
                    .setMinimumLatency(1000)
                    .setOverrideDeadline(3000)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                js.schedule(jobInfo)
                Log.d(TAG, "JobScheduler restart scheduled")
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
                    setSound(null, null)
                    setLockscreenVisibility(Notification.VISIBILITY_PRIVATE)
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
        val strictText = if (AppConfig.isStrictModeEnabled()) "严格模式已锁定" else "注意力防线激活中"
        return NotificationCompat.Builder(this, CHANNEL_KEEP_ALIVE)
            .setContentTitle("applan 守护中")
            .setContentText(strictText)
            .setSmallIcon(R.drawable.ic_lock)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()
    }
}
