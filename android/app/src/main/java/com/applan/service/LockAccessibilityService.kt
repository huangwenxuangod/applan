package com.applan.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.applan.MainActivity
import com.applan.util.AppConfig
import com.applan.util.AppState

/**
 * 无障碍服务 - AppBlock式全覆盖拦截
 *
 * 核心原理：事件驱动（零轮询）
 * - onAccessibilityEvent是系统回调，每次窗口变化（用户切换App）立即触发，毫秒级响应
 * - AI未放行时：检测到任何非白名单App → 立即显示全局遮罩BlockOverlay（毫秒级盖上去）
 * - 遮罩是TYPE_APPLICATION_OVERLAY全屏View，拦截所有触摸，用户无法操作底层App
 * - 遮罩上只有"返回applan"按钮，同时后台拉起MainActivity
 * - MainActivity到前台后自动隐藏遮罩
 * - 白名单：applan自身、系统设置相关（权限引导期间）、系统UI（状态栏等）
 * - 放行后：不拦截，用户可自由使用手机
 *
 * 性能优化：
 * - 事件驱动架构，零轮询零延迟
 * - 缓存默认Launcher包名，避免每次事件都调用PackageManager Binder
 * - 使用工作线程处理事件检查，不阻塞主线程
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

    // 性能优化：工作线程处理事件，不阻塞主线程
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // 性能优化：缓存默认Launcher包名，避免每次事件都resolveActivity
    @Volatile
    private var cachedDefaultLauncher: String? = null

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
        "com.miui.home",                    // MIUI桌面
        "com.android.launcher",
        "com.android.launcher3",
    )

    // 已知Launcher包名列表 - 避免resolveActivity Binder调用
    private val knownLaunchers = setOf(
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")

        // 性能优化：创建工作线程处理事件
        workerThread = HandlerThread("A11yWorker").apply {
            start()
            workerHandler = Handler(looper)
        }

        // 缓存默认Launcher包名
        cacheDefaultLauncher()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 只处理窗口状态变化
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // 快速路径检查（在调用线程直接判断，工作线程切换也有开销）
        // 自己App的事件 → 隐藏遮罩（App回到前台了）
        if (pkg == packageName) {
            if (BlockOverlay.isShowing()) {
                mainHandler.post { BlockOverlay.hide() }
            }
            return
        }

        // 系统白名单，不处理
        if (pkg in systemWhitelist) return

        // 性能优化：复杂判断在工作线程处理
        workerHandler?.post {
            handleWindowEvent(pkg)
        }
    }

    private fun handleWindowEvent(pkg: String) {
        try {
            // 最高优先级：AI已放行/紧急解锁已放行 → 不拦截任何东西，隐藏遮罩
            if (AppConfig.isExitGranted()) {
                if (BlockOverlay.isShowing()) {
                    mainHandler.post { BlockOverlay.hide() }
                }
                return
            }

            val shouldBlock = AppState.shouldBlock()

            // ===== 已放行状态 =====
            if (!shouldBlock) {
                // 设置宽限期内的设置包 → 允许
                if (AppState.isInSettingsGracePeriod() && isSettingsPackage(pkg)) {
                    return
                }
                // AI/紧急解锁放行 → 允许任何App，隐藏遮罩
                if (AppState.isGrantedByAi() || AppState.isGrantedByEmergency()) {
                    if (BlockOverlay.isShowing()) {
                        mainHandler.post { BlockOverlay.hide() }
                    }
                    return
                }
                // 设置宽限期内回到桌面 → 拉回applan
                if (AppState.isInSettingsGracePeriod() && isLauncherPackage(pkg)) {
                    Log.d(TAG, "Back from settings to launcher, blocking")
                    AppState.endSettingsSession()
                    performBlock()
                    return
                }
            }

            // ===== 守护模式状态：检测到任何非白名单包名 → 立即拦截 =====
            // 包括Launcher、Recents、任何第三方App
            if (shouldBlock) {
                // 设置宽限期内的设置包放行
                if (AppState.isInSettingsGracePeriod() && isSettingsPackage(pkg)) {
                    return
                }
                Log.d(TAG, "BLOCKED: $pkg - showing overlay INSTANTLY")
                performBlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling window event", e)
        }
    }

    /**
     * 执行拦截：毫秒级显示全局遮罩
     * 注意：遮罩显示必须在主线程（UI操作）
     */
    private fun performBlock() {
        mainHandler.post {
            try {
                // 立即显示全局遮罩（毫秒级响应，覆盖在所有App之上）
                BlockOverlay.show(this@LockAccessibilityService)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay, falling back to startActivity", e)
                // 降级：直接启动Activity
                fallbackPullBack()
            }
        }
    }

    /**
     * 降级方案：直接启动MainActivity
     */
    private fun fallbackPullBack() {
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
            Log.e(TAG, "Fallback pull back failed", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        workerThread?.quitSafely()
        // 服务被杀时隐藏遮罩
        mainHandler.post { BlockOverlay.hide() }
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
     * 缓存默认Launcher包名，避免每次事件都调用PackageManager
     */
    private fun cacheDefaultLauncher() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(homeIntent, 0)
            cachedDefaultLauncher = resolveInfo?.activityInfo?.packageName
            Log.d(TAG, "Cached default launcher: $cachedDefaultLauncher")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache default launcher", e)
        }
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
        if (knownLaunchers.contains(pkg)) return true
        if (cachedDefaultLauncher == pkg) return true
        return try {
            if (cachedDefaultLauncher == null) {
                cacheDefaultLauncher()
            }
            cachedDefaultLauncher == pkg && pkg != packageName
        } catch (_: Exception) {
            false
        }
    }
}
