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
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import com.applan.service.BlockOverlay
import com.applan.service.DaemonService
import com.applan.service.KeepAliveService
import com.applan.ui.ApplanApp
import com.applan.ui.consumeNeedsReset
import com.applan.ui.resetChatConversation
import com.applan.util.AppConfig
import com.applan.util.AppState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppConfig.init(this)

        val isUserLaunch = intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
                || (intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) ?: 0) != 0
        if (isUserLaunch) {
            AppConfig.setExitGranted(false)
            AppState.resetGrant()
        }

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
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
        }

        KeepAliveService.start(this)
        DaemonService.start(this)
        AppState.touch()

        // 彻底拦截系统返回键：在Activity级别添加最低优先级回调，
        // 禁用预测性返回的"再滑一次退出"动画提示。
        // Compose内的BackHandler优先级更高（默认0），会先被调用，
        // 处理Settings/Dashboard→Chat的返回。
        // 当Compose没有消费（Chat/Onboarding页）时，这个兜底callback触发，
        // 什么都不做，彻底吞掉返回事件，阻止finish()。
        onBackPressedDispatcher.addCallback(
            owner = this,
            enabled = true
        ) {
            // 什么都不做，彻底吞掉
        }.apply {
            // 设置最低优先级，确保Compose BackHandler先处理
            // 注意：OnBackPressedCallback没有直接的priority API，
            // 但我们通过早注册+不拦截来让Compose的BackHandler（晚注册）先执行
        }

        setContent {
            ApplanApp(context = this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val isUserLaunch = intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (isUserLaunch) {
            AppConfig.setExitGranted(false)
            AppState.resetGrant()
        }
        AppState.touch()
        BlockOverlay.hide()
        setupFullscreenImmersive()
        if (consumeNeedsReset()) {
            resetChatConversation()
        }
        KeepAliveService.start(this)
        DaemonService.start(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupFullscreenImmersive()
    }

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
                w.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
        } catch (e: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.onActivityResumed()
        AppState.touch()
        BlockOverlay.hide()
        setupFullscreenImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (consumeNeedsReset()) {
            resetChatConversation()
        }
        KeepAliveService.start(this)
        DaemonService.start(this)
    }

    override fun onPause() {
        super.onPause()
        AppState.onActivityPaused()
        AppState.touch()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullscreenImmersive()
        }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
    }
}
