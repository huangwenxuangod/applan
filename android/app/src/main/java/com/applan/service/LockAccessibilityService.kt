package com.applan.service

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
import com.applan.MainActivity
import com.applan.util.AppState

/**
 * 无障碍服务 - AppBlock式全覆盖拦截
 *
 * 核心原理：监听所有窗口变化事件
 * - AI未放行时：检测到任何非白名单App/Launcher/Recents → 立即拉起applan全屏覆盖
 * - 白名单：applan自身、系统设置相关（权限引导期间）、系统UI（状态栏等）
 * - 放行后：不拦截，用户可自由使用手机
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

    @Volatile
    private var isPullingBack = false

    @Volatile
    private var lastPullTime = 0L

    private val handler = Handler(Looper.getMainLooper())

    // 系统白名单包名 - 这些包名的窗口变化不触发拦截
    private val systemWhitelist = setOf(
        "android",                    // 系统对话框
        "com.android.systemui",       // 系统UI（状态栏、导航栏）
        "com.android.permissioncontroller", // 权限对话框
        "com.google.android.permissioncontroller",
    )

    // 设置相关包名 - 只有在设置宽限期内才放行
    private val settingsPackages = setOf(
        "com.android.settings",
        "com.miui.securitycenter",          // MIUI
        "com.huawei.systemmanager",         // EMUI
        "com.huawei.android.launcher",
        "com.coloros.safecenter",           // ColorOS
        "com.oppo.launcher",
        "com.vivo.permissionmanager",       // OriginOS
        "com.bbk.launcher2",
        "com.samsung.android.lool",         // One UI
        "com.sec.android.app.launcher",
        "com.miui.home",                    // MIUI桌面（特殊处理）
        "com.android.launcher",
        "com.android.launcher3",
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (isPullingBack) return

        // 只处理窗口状态变化
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // 自己App的事件，不处理
        if (pkg == packageName) return

        // 系统白名单，不处理
        if (pkg in systemWhitelist) return

        // 通过AppState判断是否应该拦截
        val shouldBlock = AppState.shouldBlock()
        if (!shouldBlock) {
            // 设置宽限期内的设置包也允许
            if (AppState.isInSettingsGracePeriod() && isSettingsPackage(pkg)) {
                return
            }
            // AI已放行，不拦截任何App
            if (AppState.isGrantedByAi() || AppState.isGrantedByEmergency()) {
                return
            }
            // 设置宽限期内回到桌面，拉回applan（用户从设置返回了）
            if (AppState.isInSettingsGracePeriod() && isLauncherPackage(pkg)) {
                Log.d(TAG, "Back from settings to launcher, pulling back")
                AppState.endSettingsSession()
                pullBackToApp()
                return
            }
        }

        // 未放行状态：检测到任何非白名单包名 → 拦截
        // 包括Launcher、Recents、任何第三方App
        if (shouldBlock) {
            // 防抖：500ms内只拉一次
            val now = System.currentTimeMillis()
            if (now - lastPullTime < 500) return
            lastPullTime = now

            Log.d(TAG, "BLOCKED: $pkg - pulling applan to foreground")
            pullBackToApp()
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
     * 执行锁屏
     */
    fun lockScreen(): Boolean {
        AppState.resetGrant()
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

    /**
     * 拉起applan全屏覆盖 - AppBlock式拦截
     * 使用FLAG_ACTIVITY_CLEAR_TOP + FLAG_ACTIVITY_SINGLE_TOP确保覆盖在当前App上面
     */
    private fun pullBackToApp() {
        isPullingBack = true
        handler.post {
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Pull back failed", e)
            }
        }
        // 防递归锁
        handler.postDelayed({
            isPullingBack = false
        }, 1000)
    }

    private fun isSettingsPackage(pkg: String): Boolean {
        if (pkg in settingsPackages) return true
        return pkg.contains("settings", ignoreCase = true) ||
               pkg.contains("permission", ignoreCase = true) ||
               pkg.contains("autostart", ignoreCase = true) ||
               pkg.contains("safecenter", ignoreCase = true) ||
               pkg.contains("securitycenter", ignoreCase = true)
    }

    private fun isLauncherPackage(pkg: String): Boolean {
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
}
