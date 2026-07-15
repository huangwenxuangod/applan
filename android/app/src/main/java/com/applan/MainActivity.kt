package com.applan

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
import com.applan.service.BlockOverlay
import com.applan.service.DaemonService
import com.applan.service.KeepAliveService
import com.applan.ui.ApplanApp
import com.applan.ui.needsResetOnResume
import com.applan.ui.resetChatConversation
import com.applan.util.AppConfig
import com.applan.util.AppState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先初始化AppConfig（DeviceProtectedStorage，锁屏可访问）
        AppConfig.init(this)

        // 判断是否是用户主动打开App：
        // 1. CATEGORY_LAUNCHER → 从桌面图标点击
        // 2. FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY → 从最近任务列表点击
        val isUserLaunch = intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
                || (intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) ?: 0) != 0
        if (isUserLaunch) {
            // 用户主动打开，重置放行标志，回到守护模式
            AppConfig.setExitGranted(false)
            AppState.resetGrant()
        }

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

        // 只在首次创建时启动服务，避免onResume重复启动
        KeepAliveService.start(this)
        DaemonService.start(this)
        AppState.touch()

        setContent {
            ApplanApp(context = this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // onNewIntent也检查是否是用户从桌面/最近任务主动打开
        val isUserLaunch = intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (isUserLaunch) {
            AppConfig.setExitGranted(false)
            AppState.resetGrant()
        }
        AppState.touch()
        BlockOverlay.hide()
        setupFullscreenImmersive()
        if (needsResetOnResume) {
            resetChatConversation()
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
        AppState.onActivityResumed()
        AppState.touch()
        // Activity到前台，隐藏全局遮罩
        BlockOverlay.hide()
        setupFullscreenImmersive()
        if (needsResetOnResume) {
            resetChatConversation()
        }
    }

    override fun onPause() {
        super.onPause()
        AppState.onActivityPaused()
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
}
