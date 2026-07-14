package com.lockai.util

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
 */
object AppState {

    private const val TAG = "AppState"

    @Volatile private var grantedByAi = false
    @Volatile private var grantedByEmergency = false
    @Volatile private var settingsGraceUntil = 0L

    private const val SETTINGS_GRACE_PERIOD = 120_000L // 2分钟

    @Volatile var lastInteractionTime: Long = System.currentTimeMillis()
        private set

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
        if (grantedByAi || grantedByEmergency) return false
        if (System.currentTimeMillis() < settingsGraceUntil) return false
        return true
    }

    fun touch() {
        lastInteractionTime = System.currentTimeMillis()
    }
}
