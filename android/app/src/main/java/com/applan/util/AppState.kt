package com.applan.util

import android.util.Log

object AppState {

    private const val TAG = "AppState"

    @Volatile private var grantedByAi = false
    @Volatile private var grantedByEmergency = false
    @Volatile private var grantedByPlan = false
    @Volatile private var currentPlan: AccessPlan? = null
    @Volatile private var lastViolatedPlan: AccessPlan? = null
    @Volatile private var settingsGraceUntil = 0L
    @Volatile private var grantTimeoutAt = 0L

    @Volatile var isActivityInForeground = false
        private set

    @Volatile private var permissionRevoked = false

    @Volatile private var lastKeepAliveStart = 0L
    @Volatile private var lastDaemonStart = 0L
    @Volatile private var lastActivityLaunch = 0L

    private const val KEEP_ALIVE_COOLDOWN_MS = 3000L
    private const val DAEMON_COOLDOWN_MS = 5000L
    private const val ACTIVITY_LAUNCH_COOLDOWN_MS = 2000L

    private const val SETTINGS_GRACE_PERIOD = 120_000L
    private const val DEFAULT_GRANT_TIMEOUT = 15 * 60 * 1000L
    private const val EMERGENCY_GRANT_TIMEOUT = 60 * 1000L
    const val PLAN_VIOLATION_DEBOUNCE_MS = 2000L

    @Volatile var lastInteractionTime: Long = System.currentTimeMillis()
        private set

    @Volatile var onViolationDetected: ((ViolationRecord) -> Unit)? = null

    fun grantByAi() {
        grantedByAi = true
        grantedByEmergency = false
        grantedByPlan = false
        currentPlan = null
        lastViolatedPlan = null
        settingsGraceUntil = 0
        grantTimeoutAt = System.currentTimeMillis() + DEFAULT_GRANT_TIMEOUT
        Log.d(TAG, "GRANTED_FULL by AI - timeout at $grantTimeoutAt")
    }

    fun grantByPlan(plan: AccessPlan) {
        grantedByPlan = true
        grantedByAi = false
        grantedByEmergency = false
        currentPlan = plan
        lastViolatedPlan = null
        settingsGraceUntil = 0
        grantTimeoutAt = plan.timeoutAt
        Log.d(TAG, "GRANTED_PLAN: ${plan.planDescription}, allowed=${plan.allowedPackages}, timeout=${plan.timeoutAt}")
    }

    fun grantByEmergency() {
        grantedByEmergency = true
        grantedByAi = false
        grantedByPlan = false
        currentPlan = null
        lastViolatedPlan = null
        settingsGraceUntil = 0
        grantTimeoutAt = System.currentTimeMillis() + EMERGENCY_GRANT_TIMEOUT
        Log.d(TAG, "GRANTED by emergency key - timeout ${EMERGENCY_GRANT_TIMEOUT}ms")
    }

    fun resetGrant() {
        if (grantedByPlan && currentPlan != null) {
            lastViolatedPlan = currentPlan
        }
        grantedByAi = false
        grantedByEmergency = false
        grantedByPlan = false
        currentPlan = null
        settingsGraceUntil = 0
        grantTimeoutAt = 0
        lastInteractionTime = System.currentTimeMillis()
        Log.d(TAG, "RESET to guarding mode")
    }

    fun startSettingsSession() {
        settingsGraceUntil = System.currentTimeMillis() + SETTINGS_GRACE_PERIOD
        grantedByAi = false
        grantedByEmergency = false
        grantedByPlan = false
        currentPlan = null
        grantTimeoutAt = 0
        Log.d(TAG, "Settings session started - ${SETTINGS_GRACE_PERIOD}ms grace period")
    }

    fun endSettingsSession() {
        settingsGraceUntil = 0
        Log.d(TAG, "Settings session ended")
    }

    fun isInSettingsGracePeriod(): Boolean = System.currentTimeMillis() < settingsGraceUntil
    fun isGrantedByAi(): Boolean = grantedByAi
    fun isGrantedByEmergency(): Boolean = grantedByEmergency
    fun isGrantedByPlan(): Boolean = grantedByPlan
    fun getCurrentPlan(): AccessPlan? = currentPlan
    fun getLastViolatedPlan(): AccessPlan? = lastViolatedPlan
    fun clearLastViolatedPlan() { lastViolatedPlan = null }

    fun isGrantExpired(): Boolean {
        if (grantTimeoutAt == 0L) return false
        return System.currentTimeMillis() >= grantTimeoutAt
    }

    fun isPackageAllowed(packageName: String): Boolean {
        if (!grantedByPlan) return false
        val plan = currentPlan ?: return false
        if (plan.allowedPackages.contains(packageName)) {
            plan.visitedPackages.add(packageName)
            plan.lastForegroundPackage = packageName
            return true
        }
        return false
    }

    fun appendAllowedPackages(packages: Set<String>, appNames: List<String>, reason: String) {
        val plan = currentPlan ?: return
        plan.allowedPackages.addAll(packages)
        plan.allowedAppNames.addAll(appNames)
        plan.violations.lastOrNull { !it.resolved }?.resolved = true
        Log.d(TAG, "Appended allowed packages: $packages, reason: $reason")
    }

    fun recordViolation(packageName: String, appName: String): ViolationRecord {
        val record = ViolationRecord(packageName = packageName, appName = appName)
        val plan = currentPlan
        if (plan != null) {
            plan.violations.add(record)
            Log.d(TAG, "VIOLATION recorded: $appName ($packageName), total violations: ${plan.violations.size}")
        } else {
            Log.d(TAG, "VIOLATION recorded (no active plan): $appName ($packageName)")
        }
        return record
    }

    fun shouldBlock(): Boolean {
        if (grantedByAi) {
            if (isGrantExpired()) {
                Log.d(TAG, "GRANTED_FULL expired, resetting")
                resetGrant()
                return true
            }
            return false
        }
        if (grantedByEmergency) {
            if (isGrantExpired()) {
                Log.d(TAG, "EMERGENCY grant expired, resetting")
                resetGrant()
                return true
            }
            return false
        }
        if (grantedByPlan) {
            if (isGrantExpired()) {
                Log.d(TAG, "GRANTED_PLAN expired, resetting")
                resetGrant()
                return true
            }
            return false
        }
        if (System.currentTimeMillis() < settingsGraceUntil) return false
        return true
    }

    fun onActivityResumed() {
        isActivityInForeground = true
        lastInteractionTime = System.currentTimeMillis()
    }

    fun onActivityPaused() {
        isActivityInForeground = false
    }

    fun canStartKeepAlive(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastKeepAliveStart < KEEP_ALIVE_COOLDOWN_MS) return false
        lastKeepAliveStart = now
        return true
    }

    fun canStartDaemon(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDaemonStart < DAEMON_COOLDOWN_MS) return false
        lastDaemonStart = now
        return true
    }

    fun canLaunchActivity(): Boolean {
        if (isActivityInForeground) return false
        if (!shouldBlock()) return false
        val now = System.currentTimeMillis()
        if (now - lastActivityLaunch < ACTIVITY_LAUNCH_COOLDOWN_MS) return false
        lastActivityLaunch = now
        return true
    }

    fun setPermissionRevoked(revoked: Boolean) {
        permissionRevoked = revoked
    }

    fun isPermissionRevoked(): Boolean = permissionRevoked

    fun clearPermissionRevoked() {
        permissionRevoked = false
    }

    fun touch() {
        lastInteractionTime = System.currentTimeMillis()
    }
}
