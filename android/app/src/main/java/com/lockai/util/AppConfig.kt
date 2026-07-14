package com.lockai.util

import android.content.Context
import android.content.SharedPreferences
import com.lockai.BuildConfig

/**
 * App配置持久化 - 存储服务器地址、API Key等用户可配置项
 */
object AppConfig {

    private const val PREF_NAME = "app_config"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"

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
        return prefs.getString(KEY_MODEL, "deepseek-v4-flash") ?: "deepseek-v4-flash"
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
}
