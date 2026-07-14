package com.lockai

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.lockai.service.DaemonService
import com.lockai.service.KeepAliveService
import com.lockai.ui.LockAIApp
import com.lockai.ui.needsResetOnResume
import com.lockai.ui.resetChatConversation
import com.lockai.util.AppConfig
import com.lockai.util.AppState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化AppConfig（DeviceProtectedStorage，锁屏可访问）
        AppConfig.init(this)

        // 确保窗口在锁屏上显示（在onCreate早期设置，window此时可用）
        try {
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
            // 屏幕常亮
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            // window可能还没准备好，忽略，后面onAttachedToWindow再设
        }

        KeepAliveService.start(this)
        DaemonService.start(this)
        AppState.touch()

        setContent {
            LockAIApp(context = this)
        }
    }

    /**
     * onAttachedToWindow时window已完全初始化，不会NPE
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupFullscreenImmersive()
    }

    /**
     * 设置全屏沉浸模式 - 隐藏状态栏和导航栏，覆盖手势区域
     * 加空判断兜底，防止window或insetsController为null
     */
    private fun setupFullscreenImmersive() {
        try {
            val w = window ?: return
            WindowCompat.setDecorFitsSystemWindows(w, false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = w.insetsController ?: return
                controller.hide(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.systemBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                w.decorView?.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
        } catch (e: Exception) {
            // 全屏设置失败不影响App使用
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.touch()
        setupFullscreenImmersive()
        if (needsResetOnResume) {
            resetChatConversation()
        }
        KeepAliveService.start(this)
        DaemonService.start(this)
    }

    override fun onPause() {
        super.onPause()
        AppState.touch()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullscreenImmersive()
        }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        // 不调用super，BackHandler在Compose中处理
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppState.touch()
        setupFullscreenImmersive()
        if (needsResetOnResume) {
            resetChatConversation()
        }
    }
}
