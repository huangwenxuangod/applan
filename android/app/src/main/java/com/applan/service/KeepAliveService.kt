package com.applan.service

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
 * 2. onTaskRemoved 立即拉起自己 + MainActivity
 * 3. onDestroy 用 AlarmManager 延迟重启
 * 4. 与 DaemonService 互相守护
 *
 * 性能优化：
 * - 使用工作线程Handler，不阻塞主线程
 * - 智能判断Activity是否已在前台，避免重复startActivity
 * - Service启动冷却机制，防止双Service互相无限启动
 * - 严格模式下监控关键权限，被关闭时立即引导重新开启
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val NOTIFICATION_ID = 10001
        private const val CHECK_INTERVAL_MS = 5000L

        fun start(context: Context) {
            // 性能优化：加启动冷却，防止短时间内重复启动
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

        fun bringToForeground(context: Context, reason: String = "check") {
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    putExtra("auto_launch", true)
                    putExtra("launch_reason", reason)
                }
                context.startActivity(intent)
                Log.d(TAG, "bringToForeground: $reason")
            } catch (e: Exception) {
                Log.e(TAG, "bringToForeground failed: $reason", e)
            }
        }
    }

    // 性能优化：使用工作线程Handler，不占用主线程
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var checkRunnable: Runnable? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "KeepAlive service created")

        // 创建工作线程，所有定时任务跑在工作线程，不阻塞UI
        handlerThread = HandlerThread("KeepAliveWorker").apply {
            start()
            handler = Handler(looper)
        }

        // 每60秒检查守护状态（从30秒改为60秒，减少唤醒频率）
        checkRunnable = object : Runnable {
            override fun run() {
                var nextInterval = 15000L // 默认15秒
                try {
                    if (AppConfig.isExitGranted()) {
                        // AI已放行（exit_app/紧急解锁）→ 不拦截，不拉起Activity，保活但不干扰
                        if (AppState.canStartDaemon()) {
                            DaemonService.start(this@KeepAliveService)
                        }
                        nextInterval = 60000L // 放行时60秒检查一次，减少资源消耗
                    } else {
                        // 严格模式下检查关键权限是否被关闭
                        if (AppConfig.isStrictModeEnabled()) {
                            checkCriticalPermissions()
                        }

                        // 检查DaemonService是否存活，加冷却判断
                        if (AppState.canStartDaemon()) {
                            DaemonService.start(this@KeepAliveService)
                        }

                        // 性能优化关键：只有真正需要时才拉起Activity
                        if (AppState.canLaunchActivity()) {
                            Log.d(TAG, "Activity not in foreground and should block, launching...")
                            launchMainActivity()
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

    /**
     * 严格模式下检查关键权限是否被关闭
     * 如果无障碍服务或悬浮窗权限被关闭，立即引导用户重新开启
     */
    private fun checkCriticalPermissions() {
        try {
            val a11yEnabled = PermissionHelper.isAccessibilityEnabled(this)
            val overlayEnabled = PermissionHelper.canDrawOverlays(this)

            if (!a11yEnabled || !overlayEnabled) {
                Log.w(TAG, "Strict mode: critical permission revoked! a11y=$a11yEnabled, overlay=$overlayEnabled")
                // 权限被关闭，立即拉起引导页（使用Onboarding而不是Chat）
                // 通过设置特殊标志让MainActivity显示权限缺失警告
                AppState.setPermissionRevoked(true)
                launchMainActivity()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check permissions in strict mode", e)
        }
    }

    private fun launchMainActivity() {
        try {
            // 先用全局遮罩即时拦截（毫秒级），再后台拉起Activity
            if (AppState.shouldBlock() && !AppState.isActivityInForeground) {
                mainHandler.post { BlockOverlay.show(this@KeepAliveService) }
            }
            val intent = Intent(this@KeepAliveService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch activity", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // 启动DaemonService时加冷却判断
        if (AppState.canStartDaemon()) {
            DaemonService.start(this)
        }
        // START_STICKY：被杀后系统会尝试重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved - app was swiped away, restarting everything!")
        try {
            launchMainActivity()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart MainActivity on task removed", e)
        }
        try {
            val restartService = Intent(this, KeepAliveService::class.java)
            ContextCompat.startForegroundService(this, restartService)
        } catch (_: Exception) {}
        scheduleRestart(100)
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
                        if (AppState.shouldBlock()) {
                            bringToForeground(context, "screen_on")
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
            Log.d(TAG, "Screen receiver registered")
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
            Log.d(TAG, "Restart scheduled in ${delayMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Schedule restart failed", e)
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
