package com.applan

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.applan.util.AppConfig
import com.applan.util.CrashHandler

class ApplanApp : Application() {

    companion object {
        lateinit var instance: ApplanApp
            private set
    }

    override fun attachBaseContext(base: Context?) {
        // 使用DeviceProtectedStorage，锁屏状态下也能访问SP
        // 修复: "SharedPreferences in credential encrypted storage are not available until after user is unlocked"
        super.attachBaseContext(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && base != null) {
                base.createDeviceProtectedStorageContext()
            } else {
                base
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化AppConfig（必须在任何Service/Activity之前）
        // 修复：Service可能在Activity之前被系统启动（开机自启/AlarmManager重启），
        // 如果不在Application中初始化，Service访问AppConfig时会崩溃
        AppConfig.init(this)

        // 初始化全局崩溃处理器（必须最先初始化）
        CrashHandler.init(this)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                CHANNEL_KEEP_ALIVE,
                "锁屏保护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "applan正在守护你的注意力"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }

            nm.createNotificationChannel(channel)
        }
    }
}

const val CHANNEL_KEEP_ALIVE = "appplan_service"
