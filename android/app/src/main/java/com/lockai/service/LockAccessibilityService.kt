package com.lockai.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.lockai.MainActivity

/**
 * 无障碍服务 - 双重功能：
 * 1. 执行锁屏操作（GLOBAL_ACTION_LOCK_SCREEN）
 * 2. 检测用户按Home/Recents试图绕过，自动拉回LockAI
 */
class LockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LockA11y"
        private var instance: LockAccessibilityService? = null

        fun getInstance(): LockAccessibilityService? = instance

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, LockAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').contains(expected)
        }
    }

    // 标记：AI是否已放行。放行后不再拦截Home键
    @Volatile
    private var isGranted = false

    // 防止无限递归拉起
    @Volatile
    private var isPullingBack = false

    private val handler = Handler(Looper.getMainLooper())

    fun setGranted(granted: Boolean) {
        isGranted = granted
        Log.d(TAG, "Granted state changed: $granted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isGranted = false
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (isGranted) return
        if (isPullingBack) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                val cls = event.className?.toString() ?: return

                val isLauncher = isHomePackage(pkg)
                val isRecents = isRecentsClass(cls)

                if (isLauncher || isRecents) {
                    Log.d(TAG, "Escape attempt detected: pkg=$pkg, cls=$cls")
                    pullBackToLockAI()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * 执行锁屏 - GLOBAL_ACTION_LOCK_SCREEN (API 28+)
     */
    fun lockScreen(): Boolean {
        isGranted = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "Lock screen requires API 28+")
            return false
        }
        return try {
            val result = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            Log.d(TAG, "Lock screen: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Lock screen failed", e)
            false
        }
    }

    private fun pullBackToLockAI() {
        isPullingBack = true
        try {
            // 先按Home关闭Recents面板
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Exception) {}
        // 延迟150ms后拉起LockAI，避免与系统动画冲突
        handler.postDelayed({
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Pull back failed", e)
            }
        }, 150)
        // 1.5秒后重置防递归标志
        handler.postDelayed({
            isPullingBack = false
        }, 1500)
    }

    private fun isHomePackage(pkg: String): Boolean {
        val knownLaunchers = setOf(
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.bbk.launcher2",
            "com.vivo.launcher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.microsoft.launcher",
            "com.nova.launcher",
            "org.lineageos.launcher",
            "app.lawnchair"
        )
        if (knownLaunchers.contains(pkg)) return true
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(homeIntent, 0)
            resolveInfo?.activityInfo?.packageName == pkg && pkg != packageName
        } catch (_: Exception) {
            false
        }
    }

    private fun isRecentsClass(cls: String): Boolean {
        return cls.contains("RecentsActivity", ignoreCase = true) ||
               cls.contains("RecentTasksActivity", ignoreCase = true) ||
               cls.contains("OverviewProxyService", ignoreCase = true) ||
               cls.contains("RecentsAndfs", ignoreCase = true)
    }
}
