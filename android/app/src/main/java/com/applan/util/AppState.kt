package com.applan.util

import android.util.Log

/**
 * 全局App状态管理器 - 协调拦截/放行状态
 *
 * 状态机：
 * - 默认（守护模式）：shouldBlock()=true，拦截一切非白名单App
 * - AI放行（grantByAi）：shouldBlock()=false，用户可自由使用
 * - 紧急解锁（grantByEmergency）：shouldBlock()=false
 * - 设置宽限期（startSettingsSession）：2分钟内允许系统设置，结束回到守护模式
 * - 重置（resetGrant）：回到守护模式
 * - 严格模式权限撤销（permissionRevoked）：强制拉起引导页让用户重新开启权限
 */
object AppState {

    private const val TAG = "AppState"

    @Volatile private var grantedByAi = false
    @Volatile private var grantedByEmergency = false
    @Volatile private var settingsGraceUntil = 0L

    private const val SETTINGS_GRACE_PERIOD = 120_000L // 2分钟

    @Volatile var lastInteractionTime: Long = System.currentTimeMillis()
        private set

    // ========== 新增：前台状态和启动冷却，解决性能问题 ==========
    @Volatile var isActivityInForeground: Boolean = false
        private set

    // 上次启动Activity的时间戳，防止频繁拉起
    @Volatile private var lastActivityLaunchTime: Long = 0L
    private const val MIN_ACTIVITY_LAUNCH_INTERVAL = 1_000L // 最小拉起间隔1秒（遮罩即时拦截，不需要太长冷却）

    // 上次启动Service的时间戳，防止双Service互相无限启动
    @Volatile private var lastKeepAliveStartTime: Long = 0L
    @Volatile private var lastDaemonStartTime: Long = 0L
    private const val MIN_SERVICE_START_INTERVAL = 5_000L // Service最小启动间隔5秒

    // 严格模式下权限被撤销标志
    @Volatile private var permissionRevoked = false

    fun onActivityResumed() {
        isActivityInForeground = true
        Log.d(TAG, "Activity moved to foreground")
    }

    fun onActivityPaused() {
        isActivityInForeground = false
        Log.d(TAG, "Activity moved to background")
    }

    /**
     * 设置权限被撤销标志（严格模式下由KeepAliveService检测到权限关闭时调用）
     */
    fun setPermissionRevoked(revoked: Boolean) {
        permissionRevoked = revoked
        if (revoked) {
            Log.w(TAG, "PERMISSION REVOKED detected in strict mode!")
        }
    }

    fun isPermissionRevoked(): Boolean = permissionRevoked

    fun clearPermissionRevoked() {
        permissionRevoked = false
    }

    /**
     * 检查是否可以启动Activity（带冷却机制，防止频繁拉起）
     */
    fun canLaunchActivity(): Boolean {
        val now = System.currentTimeMillis()
        // 权限被撤销时，强制允许拉起
        if (permissionRevoked) {
            if (now - lastActivityLaunchTime < MIN_ACTIVITY_LAUNCH_INTERVAL) return false
            lastActivityLaunchTime = now
            return true
        }
        if (!shouldBlock()) return false // 已经放行不需要拉起
        if (isActivityInForeground) return false // 已经在前台不需要拉起
        if (now - lastActivityLaunchTime < MIN_ACTIVITY_LAUNCH_INTERVAL) return false // 冷却中
        lastActivityLaunchTime = now
        return true
    }

    /**
     * 检查是否可以启动KeepAliveService（防止重复启动）
     */
    fun canStartKeepAlive(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastKeepAliveStartTime < MIN_SERVICE_START_INTERVAL) return false
        lastKeepAliveStartTime = now
        return true
    }

    /**
     * 检查是否可以启动DaemonService（防止重复启动）
     */
    fun canStartDaemon(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDaemonStartTime < MIN_SERVICE_START_INTERVAL) return false
        lastDaemonStartTime = now
        return true
    }
    // ========== 状态管理结束 ==========

    fun grantByAi() {
        grantedByAi = true
        grantedByEmergency = false
        settingsGraceUntil = 0
        Log.d(TAG, "GRANTED by AI - user can use phone freely")
    }

    fun grantByEmergency() {
        grantedByEmergency = true
        grantedByAi = false
        settingsGraceUntil = 0
        Log.d(TAG, "GRANTED by emergency key")
    }

    fun resetGrant() {
        grantedByAi = false
        grantedByEmergency = false
        settingsGraceUntil = 0
        lastInteractionTime = System.currentTimeMillis()
        Log.d(TAG, "RESET to guarding mode")
    }

    fun startSettingsSession() {
        settingsGraceUntil = System.currentTimeMillis() + SETTINGS_GRACE_PERIOD
        grantedByAi = false
        grantedByEmergency = false
        Log.d(TAG, "Settings session started - ${SETTINGS_GRACE_PERIOD}ms grace period")
    }

    fun endSettingsSession() {
        settingsGraceUntil = 0
        Log.d(TAG, "Settings session ended")
    }

    fun isInSettingsGracePeriod(): Boolean {
        return System.currentTimeMillis() < settingsGraceUntil
    }

    fun isGrantedByAi(): Boolean = grantedByAi
    fun isGrantedByEmergency(): Boolean = grantedByEmergency

    /**
     * 是否应该拦截（即是否处于守护模式）
     */
    fun shouldBlock(): Boolean {
        // 持久化放行标志最高优先级：AI exit_app或紧急解锁后不再拦截
        if (AppConfig.isExitGranted()) return false
        if (grantedByAi || grantedByEmergency) return false
        if (System.currentTimeMillis() < settingsGraceUntil) return false
        return true
    }

    fun touch() {
        lastInteractionTime = System.currentTimeMillis()
    }
}
