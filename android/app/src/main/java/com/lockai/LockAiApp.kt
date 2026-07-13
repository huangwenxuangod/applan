package com.lockai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.lockai.service.KeepAliveService

class LockAiApp : Application() {

    companion object {
        lateinit var instance: LockAiApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        // 启动保活服务
        KeepAliveService.start(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val keepAliveChannel = NotificationChannel(
                CHANNEL_KEEP_ALIVE,
                getString(R.string.channel_keep_alive),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_keep_alive_desc)
                setShowBadge(false)
            }

            val lockScreenChannel = NotificationChannel(
                CHANNEL_LOCK_SCREEN,
                getString(R.string.channel_lock_screen),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_lock_screen_desc)
            }

            nm.createNotificationChannels(listOf(keepAliveChannel, lockScreenChannel))
        }
    }
}

const val CHANNEL_KEEP_ALIVE = "keep_alive_channel"
const val CHANNEL_LOCK_SCREEN = "lock_screen_channel"
