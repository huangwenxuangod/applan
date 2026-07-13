package com.lockai.ui

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lockai.BuildConfig
import com.lockai.service.KeepAliveService
import com.lockai.ui.chat.ChatScreen
import com.lockai.ui.chat.ChatViewModel
import com.lockai.ui.emergency.EmergencyUnlockScreen
import com.lockai.ui.onboarding.OnboardingScreen
import com.lockai.ui.settings.SettingsScreen
import com.lockai.ui.theme.LockAITheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class Screen {
    object Onboarding : Screen()
    object Chat : Screen()
    object Settings : Screen()
    object EmergencyUnlock : Screen()
}

private const val PREFS_NAME = "lockai_prefs"
private const val KEY_ONBOARDED = "has_completed_onboarding"

// 全局标记：下次App回到前台时是否需要重置
@Volatile
var needsResetOnResume = false

private var chatViewModelRef: ChatViewModel? = null

// 重置事件流
private val _resetFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val resetFlow = _resetFlow.asSharedFlow()

fun resetChatConversation() {
    chatViewModelRef?.resetConversation()
    _resetFlow.tryEmit(Unit)
    needsResetOnResume = false
}

fun markNeedsReset() {
    needsResetOnResume = true
}

@Composable
fun LockAIApp(context: Context) {
    LockAITheme {
        val activity = context as? Activity
        val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
        var hasOnboarded by remember {
            mutableStateOf(prefs.getBoolean(KEY_ONBOARDED, false))
        }

        val serverUrl = BuildConfig.SERVER_URL
        val apiKey = BuildConfig.API_KEY

        var currentScreen by remember {
            mutableStateOf<Screen>(if (hasOnboarded) Screen.Chat else Screen.Onboarding)
        }
        val chatViewModel: ChatViewModel = viewModel()

        // 监听重置事件，导航回Chat
        LaunchedEffect(Unit) {
            resetFlow.collect {
                currentScreen = Screen.Chat
            }
        }

        DisposableEffect(Unit) {
            chatViewModelRef = chatViewModel
            onDispose { chatViewModelRef = null }
        }

        LaunchedEffect(Unit) {
            chatViewModel.updateConfig(serverUrl, apiKey)
            if (hasOnboarded) {
                KeepAliveService.start(context)
            }
        }

        // 拦截返回键
        BackHandler(enabled = currentScreen == Screen.Chat || currentScreen == Screen.Onboarding) {
            when (currentScreen) {
                is Screen.Chat -> {
                    markNeedsReset()
                    activity?.moveTaskToBack(true)
                }
                is Screen.Onboarding -> {} // 引导页不允许返回
                else -> {}
            }
        }

        fun completeOnboarding() {
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
            hasOnboarded = true
            KeepAliveService.start(context)
            currentScreen = Screen.Chat
        }

        fun emergencyUnlock() {
            markNeedsReset()
            activity?.moveTaskToBack(true)
            currentScreen = Screen.Chat
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Crossfade(
                targetState = currentScreen,
                animationSpec = tween(durationMillis = 200),
                label = "screen_nav"
            ) { screen ->
                when (screen) {
                    is Screen.Onboarding -> OnboardingScreen(
                        onAllGranted = { completeOnboarding() }
                    )
                    is Screen.Chat -> ChatScreen(
                        viewModel = chatViewModel,
                        onSettingsClick = { currentScreen = Screen.Settings },
                        onEmergencyUnlock = { currentScreen = Screen.EmergencyUnlock }
                    )
                    is Screen.Settings -> SettingsScreen(
                        onBack = { currentScreen = Screen.Chat },
                        onEmergencyUnlock = { currentScreen = Screen.EmergencyUnlock }
                    )
                    is Screen.EmergencyUnlock -> EmergencyUnlockScreen(
                        onUnlockSuccess = { emergencyUnlock() },
                        onBack = { currentScreen = Screen.Chat }
                    )
                }
            }
        }
    }
}
