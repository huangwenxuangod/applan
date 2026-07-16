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

    @Volatile
    private var isPullingBack = false

    @Volatile
    private var lastPullTime = 0L

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

    private val systemWhitelist = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.huawei.android.launcher",
        "com.coloros.safecenter",
        "com.oppo.launcher",
        "com.vivo.permissionmanager",
        "com.bbk.launcher2",
        "com.samsung.android.lool",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.android.launcher",
        "com.android.launcher3",
    )

    private val settingsPrefixPatterns = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.vivo.permissionmanager",
        "com.samsung.android.lool",
        "com.sec.android",
        "com.oneplus.security",
        "com.meizu.safe",
        "com.zui.safecenter",
    )

    private val settingsKeywords = setOf(
        "settings", "security", "permission", "safecenter", "appmanager",
        "privacy", "safe", "guard", "protect", "autostart", "permissionmanager"
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
        "app.lawnchair"
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
            if (pkg != packageName && pkg !in systemWhitelist) {
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

        if (pkg == packageName) {
            cancelPendingViolation()
            if (BlockOverlay.isShowing()) {
                mainHandler.post { BlockOverlay.hide() }
            }
            return
        }

        if (pkg in systemWhitelist) {
            return
        }

        workerHandler?.post {
            handleWindowEvent(pkg)
        }
    }

    private fun handleWindowEvent(pkg: String) {
        try {
            if (AppConfig.isExitGranted()) {
                if (BlockOverlay.isShowing()) {
                    mainHandler.post { BlockOverlay.hide() }
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
                        mainHandler.post { BlockOverlay.hide() }
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
        mainHandler.post {
            try {
                BlockOverlay.show(this@LockAccessibilityService)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay, falling back to startActivity", e)
                fallbackPullBack(pkg)
            }
        }
        if (!isPullingBack) {
            val now = System.currentTimeMillis()
            if (now - lastPullTime < 500) return
            lastPullTime = now
            pullBackToApp()
        }
    }

    private fun pullBackToApp() {
        isPullingBack = true
        mainHandler.post {
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
        mainHandler.postDelayed({
            isPullingBack = false
        }, 1000)
    }

    private fun pullBackToAppForChallenge(record: ViolationRecord) {
        isPullingBack = true
        mainHandler.post {
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                    putExtra("challenge_mode", true)
                    putExtra("violating_package", record.packageName)
                    putExtra("violating_app_name", record.appName)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Pull back for challenge failed", e)
                pullBackToApp()
            }
        }
        mainHandler.postDelayed({
            isPullingBack = false
        }, 1000)
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
