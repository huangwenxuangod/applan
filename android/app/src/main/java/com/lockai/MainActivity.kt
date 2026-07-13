package com.lockai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.lockai.service.KeepAliveService
import com.lockai.ui.LockAIApp
import com.lockai.ui.needsResetOnResume
import com.lockai.ui.resetChatConversation

class MainActivity : ComponentActivity() {

    private var isFirstCreate = true

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        KeepAliveService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 在锁屏上显示 + 自动亮屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        requestNotificationPermissionIfNeeded()
        KeepAliveService.start(this)

        setContent {
            LockAIApp(context = this)
        }

        isFirstCreate = false
    }

    override fun onResume() {
        super.onResume()
        KeepAliveService.start(this)
        // 只有在标记需要重置时才重置（锁屏后解锁、退出后回来、返回桌面后回来）
        // 从设置页/紧急解锁页返回时不重置
        if (needsResetOnResume) {
            resetChatConversation()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 拦截返回键，回到桌面而不是退出应用
        moveTaskToBack(true)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
