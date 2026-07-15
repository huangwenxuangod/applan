package com.applan.util

import android.content.Context
import android.content.SharedPreferences
import com.applan.BuildConfig

/**
 * App配置持久化 - 存储服务器地址、API Key等用户可配置项
 */
object AppConfig {

    private const val PREF_NAME = "app_config"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_STRICT_MODE = "strict_mode_enabled"
    private const val KEY_EXIT_GRANTED = "exit_granted"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        // 使用DeviceProtectedStorage，锁屏状态也能访问
        val ctx = try {
            context.createDeviceProtectedStorageContext()
        } catch (_: Exception) {
            context
        }
        prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, BuildConfig.SERVER_URL) ?: BuildConfig.SERVER_URL
    }

    fun getApiKey(): String {
        return prefs.getString(KEY_API_KEY, BuildConfig.API_KEY) ?: BuildConfig.API_KEY
    }

    fun getModel(): String {
        return prefs.getString(KEY_MODEL, "deepseek-chat") ?: "deepseek-chat"
    }

    fun saveServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun saveModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model.trim()).apply()
    }

    /**
     * 严格模式：权限一旦开启就不能关闭
     * 当所有必需权限开启后自动启用，启用后设置页面的权限开关锁定
     */
    fun isStrictModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_STRICT_MODE, false)
    }

    fun enableStrictMode() {
        prefs.edit().putBoolean(KEY_STRICT_MODE, true).apply()
    }

    /**
     * 退出放行标志：AI调用exit_app或紧急解锁后设为true
     * true时：AccessibilityService不拦截、KeepAliveService不拉起Activity、遮罩不显示
     * 用户下次主动点击图标打开App时重置为false
     */
    fun isExitGranted(): Boolean {
        return prefs.getBoolean(KEY_EXIT_GRANTED, false)
    }

    fun setExitGranted(granted: Boolean) {
        prefs.edit().putBoolean(KEY_EXIT_GRANTED, granted).apply()
    }

    /**
     * 注意：不提供disableStrictMode的public方法，一旦启用无法关闭
     * 只能通过清除应用数据重置
     */
}
