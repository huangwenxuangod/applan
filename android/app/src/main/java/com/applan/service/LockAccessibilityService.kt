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
import com.applan.util.AppPackageResolver
import com.applan.util.AppState
import com.applan.util.PolicyRepository
import com.applan.util.PolicyEventStore
import com.applan.util.ViolationRecord

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

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var cachedDefaultLauncher: String? = null

    private var pendingViolationPkg: String? = null
    private val violationCheckRunnable = Runnable {
        val pkg = pendingViolationPkg ?: return@Runnable
        pendingViolationPkg = null
        if (!AppState.shouldBlock()) return@Runnable
        if (AppState.isInSettingsGracePeriod() && isSettingsPackage(pkg)) return@Runnable

        val appName = AppPackageResolver.getAppName(this, pkg)
        Log.d(TAG, "VIOLATION confirmed: $appName ($pkg)")
        val record = AppState.recordViolation(pkg, appName)
        AppState.onViolationDetected?.invoke(record)
    }

    /**
     * 系统核心白名单 - 这些包永远不会被拦截
     */
    private val systemWhitelist = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /**
     * IME/输入法白名单 - 键盘弹出不应该触发拦截
     * 当用户在我们自己的App内时，键盘弹出属于正常操作
     * 当用户在其他App时，遮罩已经覆盖屏幕，键盘也操作不了
     */
    private val imeWhitelist = setOf(
        // 谷歌键盘
        "com.google.android.inputmethod.latin",
        "com.google.android.apps.inputmethod.hindi",
        "com.google.android.apps.inputmethod.korean",
        "com.google.android.apps.inputmethod.pinyin",
        "com.google.android.apps.inputmethod.zhuyin",
        "com.google.android.apps.inputmethod.japanese",
        "com.android.inputmethod.latin",
        "com.android.inputmethod.pinyin",
        // 百度输入法
        "com.baidu.input",
        "com.baidu.input_mi",
        "com.baidu.input_huawei",
        "com.baidu.input_oppo",
        "com.baidu.input_vivo",
        "com.baidu.input_meizu",
        // 搜狗输入法
        "com.sohu.inputmethod.sogou",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.sohu.inputmethod.sogouoem",
        // 腾讯QQ输入法
        "com.tencent.qqpinyin",
        "com.tencent.wetype",
        // 讯飞输入法
        "com.iflytek.inputmethod",
        "com.iflytek.inputmethod.pro",
        "com.iflytek.inputmethod.miui",
        // 三星键盘
        "com.samsung.android.honeyboard",
        "com.sec.android.inputmethod",
        "com.samsung.android.inputmethod.language",
        // 微软SwiftKey
        "com.touchtype.swiftkey",
        "com.swiftkey.swiftkey",
        "com.microsoft.swiftkey",
        // 其他常见键盘
        "org.pocketworkstation.pckeyboard",   // Hacker's Keyboard
        "jp.co.omronsoft.openwnn",              // OpenWnn
        "com.jb.gokeyboard",                    // GO Keyboard
        "com.emoji.keyboard.kika",              // Kika Keyboard
        "com.facemoji.lite",                    // Facemoji
        "com.syntellia.fleksy.keyboard",        // Fleksy
        "com.grammarly.android.keyboard",       // Grammarly Keyboard
        "com.netease.youdao.input",             // 有道输入法
        "com.xinshuru.inputmethod",             // 手心输入法
        "com.iqoo.ime",                         // iQOO自带输入法
        "com.vivo.inputmethod",                 // Vivo自带输入法
        "com.oppo.inputmethod",                 // OPPO自带输入法
        "com.miui.inputmethod",                 // 小米自带输入法
        "com.miui.catcherpatch",                // 小米键盘补丁
        "com.coloros.inputmethod",              // ColorOS输入法
        "com.huawei.inputmethod",               // 华为输入法
        "com.huawei.ohos.inputmethod",          // 鸿蒙输入法
        "com.nubia.inputmethod",                // 努比亚输入法
        // TTS/语音输入（也会弹window）
        "com.svox.pico",
        "com.google.android.tts",
        "com.iflytek.speechcloud",
        "com.iflytek.vflynote",
        "com.baidu.duersdk.opensdk",
        "com.google.android.googlequicksearchbox", // 语音搜索弹窗
        // 系统UI相关弹窗
        "com.android.systemui.recents",         // 最近任务
        "com.android.keychain",                 // 证书选择
        "com.android.documentsui",              // 文件选择器（被系统调用时）
        "com.google.android.documentsui",
        // 华为安全输入法/密码键盘
        "com.huawei.securitymgr",
        "com.huawei.password",
        // 小米安全键盘
        "com.miui.securitykeyboard",
        "com.miui.securitycore",
        // OPPO安全键盘
        "com.coloros.safekeyboard",
        "com.oplus.safekeyboard",
        // Vivo安全键盘
        "com.vivo.safecenter",
        "com.bbk.account",
    )

    /**
     * 判断是否是IME/输入法包名（前缀匹配）
     */
    private fun isImePackage(pkg: String): Boolean {
        if (pkg in imeWhitelist) return true
        val lower = pkg.lowercase()
        return lower.contains("inputmethod") ||
                lower.contains("keyboard") ||
                lower.contains("ime.") ||
                lower.endsWith(".ime") ||
                lower.contains(".keyboard")
    }

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.huawei.android.launcher",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.oppo.launcher",
        "com.vivo.permissionmanager",
        "com.bbk.launcher2",
        "com.samsung.android.lool",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.android.launcher",
        "com.android.launcher3",
        "com.iqoo.secure",
        "com.hihonor.systemmanager",
        "com.samsung.android.sm",
        "com.samsung.android.sm_cn",
        "com.meizu.safe",
    )

    private val settingsPrefixPatterns = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
        "com.samsung.android.lool",
        "com.sec.android",
        "com.oneplus.security",
        "com.meizu.safe",
        "com.zui.safecenter",
        "com.hihonor.systemmanager",
    )

    private val settingsKeywords = setOf(
        "settings", "security", "permission", "safecenter", "appmanager",
        "privacy", "safe", "guard", "protect", "autostart", "permissionmanager",
        "startup", "startupmgr", "appcontrol"
    )

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
        "app.lawnchair",
        "com.oplus.dragonspeedlauncher",
        "com.coloros.launcher",
        "com.hihonor.android.launcher",
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")

        workerThread = HandlerThread("A11yWorker").apply {
            start()
            workerHandler = Handler(looper)
        }

        cacheDefaultLauncher()

        mainHandler.postDelayed({
            checkForegroundAppOnConnect()
        }, 500)
    }

    private fun checkForegroundAppOnConnect() {
        try {
            val rootNode = rootInActiveWindow ?: return
            val pkg = rootNode.packageName?.toString() ?: return
            if (pkg != packageName && pkg !in systemWhitelist && !isImePackage(pkg)) {
                workerHandler?.post {
                    handleWindowEvent(pkg)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "checkForegroundAppOnConnect failed: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // ===== 关键修复1：当我们的Activity在前台时，忽略所有非App包名事件 =====
        // 这可以防止键盘(IME)、系统弹窗、对话框等临时窗口触发拦截
        // 因为当我们的Activity真的被切换走时，onPause会先设置isActivityInForeground=false
        if (pkg != packageName && AppState.isActivityInForeground) {
            // 我们的Activity在前台，这个事件是键盘/对话框/系统弹窗等临时窗口
            // 不应该触发拦截
            Log.d(TAG, "Ignoring transient window while our app is foreground: $pkg")
            return
        }

        if (pkg == packageName) {
            cancelPendingViolation()
            // ===== 关键修复2：区分我们的Activity和我们的遮罩窗口 =====
            // 遮罩(TYPE_APPLICATION_OVERLAY)显示时也会触发pkg=com.applan的事件
            // 但此时isActivityInForeground=false（遮罩不是Activity）
            // 只有当isActivityInForeground=true时才是真正回到了App
            if (BlockOverlay.isShowing()) {
                if (AppState.isActivityInForeground) {
                    // 真正的Activity回到前台 → 隐藏遮罩
                    Log.d(TAG, "Our activity is foreground, hiding overlay")
                    mainHandler.post { BlockOverlay.hide() }
                } else if (BlockOverlay.wasJustShown(1500)) {
                    // 遮罩刚显示(<1.5秒)，这是遮罩自身的窗口事件 → 不要隐藏!
                    Log.d(TAG, "Ignoring overlay's own window event (just shown)")
                } else {
                    // 遮罩显示已超过1.5秒但isActivityInForeground仍为false
                    // 可能是Activity正在启动中（onResume还没调用），延迟检查
                    Log.d(TAG, "Overlay showing but activity not foreground yet, delayed check")
                    mainHandler.postDelayed({
                        if (AppState.isActivityInForeground) {
                            BlockOverlay.hide()
                        }
                    }, 500)
                }
            }
            return
        }

        if (pkg in systemWhitelist) {
            return
        }

        // IME/输入法包名直接跳过（即使在其他App中键盘弹出也不应该重复触发拦截，
        // 因为遮罩已经在显示了，BlockOverlay.show()内部有isShowing检查）
        if (isImePackage(pkg)) {
            Log.d(TAG, "Ignoring IME window: $pkg")
            return
        }

        workerHandler?.post {
            handleWindowEvent(pkg)
        }
    }

    private fun handleWindowEvent(pkg: String) {
        try {
            val policy = PolicyRepository(this).evaluate()
            if (policy.isActive) {
                if (pkg in policy.allowedPackages) {
                    cancelPendingViolation()
                    mainHandler.post { BlockOverlay.hideImmediately() }
                    return
                }
                Log.d(TAG, "POLICY BLOCKED: $pkg")
                performBlock(pkg)
                return
            }

            if (AppConfig.isExitGranted()) {
                if (BlockOverlay.isShowing()) {
                    mainHandler.post { BlockOverlay.hideImmediately() }
                }
                return
            }

            val shouldBlock = AppState.shouldBlock()

            if (!shouldBlock) {
                if (AppState.isInSettingsGracePeriod() && isSettingsPackage(pkg)) {
                    return
                }
                if (AppState.isGrantedByAi() || AppState.isGrantedByEmergency()) {
                    if (BlockOverlay.isShowing()) {
                        mainHandler.post { BlockOverlay.hideImmediately() }
                    }
                    return
                }
                if (AppState.isInSettingsGracePeriod() && isLauncherPackage(pkg)) {
                    Log.d(TAG, "Back from settings to launcher, blocking")
                    AppState.endSettingsSession()
                    performBlock(pkg)
                    return
                }
                if (AppState.isGrantedByPlan()) {
                    if (AppState.isPackageAllowed(pkg)) {
                        cancelPendingViolation()
                        if (BlockOverlay.isShowing()) {
                            mainHandler.post { BlockOverlay.hide() }
                        }
                        return
                    }
                    if (isLauncherPackage(pkg)) {
                        return
                    }
                    Log.d(TAG, "PLAN VIOLATION: $pkg not in allowed list")
                    scheduleViolationCheck(pkg)
                    performBlock(pkg)
                    return
                }
            }

            if (shouldBlock) {
                if (AppState.isInSettingsGracePeriod() && isSettingsPackage(pkg)) {
                    return
                }
                if (isLauncherPackage(pkg)) {
                    performBlock(pkg)
                    return
                }
                Log.d(TAG, "BLOCKED: $pkg - showing overlay")
                performBlock(pkg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling window event", e)
        }
    }

    private fun performBlock(pkg: String) {
        // 只显示遮罩，不自动拉起Activity - 用户必须点击"返回applan"按钮
        mainHandler.post {
            try {
                // 如果遮罩刚显示（<1秒内），不再重复show（防抖）
                if (BlockOverlay.wasJustShown(1000)) {
                    Log.d(TAG, "Overlay just shown, skipping duplicate show")
                    return@post
                }
                PolicyEventStore(this@LockAccessibilityService).record("app_blocked", pkg)
                BlockOverlay.show(this@LockAccessibilityService)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay, falling back to startActivity", e)
                fallbackPullBack(pkg)
            }
        }
    }

    private fun fallbackPullBack(pkg: String) {
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

    private fun scheduleViolationCheck(pkg: String) {
        pendingViolationPkg = pkg
        mainHandler.removeCallbacks(violationCheckRunnable)
        mainHandler.postDelayed(violationCheckRunnable, AppState.PLAN_VIOLATION_DEBOUNCE_MS)
    }

    private fun cancelPendingViolation() {
        pendingViolationPkg = null
        mainHandler.removeCallbacks(violationCheckRunnable)
    }

    private fun isSettingsPackage(pkg: String): Boolean {
        if (pkg in settingsPackages) return true
        val lowerPkg = pkg.lowercase()
        val segments = lowerPkg.split('.')
        if (segments.size >= 3) {
            val matchesKnownVendor = settingsPrefixPatterns.any { lowerPkg.startsWith(it) }
            val lastSegment = segments.last()
            val matchesKeyword = settingsKeywords.any { lastSegment.contains(it) }
            if (matchesKnownVendor && matchesKeyword) return true
        }
        return false
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

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        workerThread?.quitSafely()
        cancelPendingViolation()
        mainHandler.post { BlockOverlay.hide() }
        Log.d(TAG, "Accessibility service destroyed")
    }

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
}
