package com.applan.util

import android.content.Context

class AuditNoticeGate(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun shouldNotify(problem: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = "$KEY_PREFIX$problem"
        val lastShown = preferences.getLong(key, 0L)
        if (lastShown != 0L && now - lastShown < THROTTLE_MS) return false
        preferences.edit().putLong(key, now).apply()
        return true
    }

    companion object {
        const val THROTTLE_MS = 6 * 60 * 60 * 1000L
        private const val PREF_NAME = "dashboard_audit_notice"
        private const val KEY_PREFIX = "last_shown_"
    }
}
