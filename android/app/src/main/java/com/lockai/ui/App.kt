package com.lockai.ui

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lockai.BuildConfig
import com.lockai.service.KeepAliveService
import com.lockai.service.LockAccessibilityService
import com.lockai.ui.chat.ChatScreen
import com.lockai.ui.chat.ChatViewModel
import com.lockai.ui.emergency.EmergencyUnlockScreen
import com.lockai.ui.onboarding.OnboardingScreen
import com.lockai.ui.settings.SettingsScreen
import com.lockai.ui.theme.LockAITheme
import com.lockai.util.AppUpdateManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed class Screen {
    object Onboarding : Screen()
    object Chat : Screen()
    object Settings : Screen()
    object EmergencyUnlock : Screen()
}

private const val PREFS_NAME = "lockai_prefs"
private const val KEY_ONBOARDED = "has_completed_onboarding"

@Volatile
var needsResetOnResume = false

private var chatViewModelRef: ChatViewModel? = null

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
        val scope = rememberCoroutineScope()
        var hasOnboarded by remember {
            mutableStateOf(prefs.getBoolean(KEY_ONBOARDED, false))
        }

        val serverUrl = BuildConfig.SERVER_URL
        val apiKey = BuildConfig.API_KEY

        var currentScreen by remember {
            mutableStateOf<Screen>(if (hasOnboarded) Screen.Chat else Screen.Onboarding)
        }
        var showUpdateDialog by remember { mutableStateOf<AppUpdateManager.UpdateInfo?>(null) }
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
                // 启动后检查更新
                val update = AppUpdateManager.checkForUpdate()
                if (update != null) {
                    showUpdateDialog = update
                }
            }
        }

        // 更新对话框
        showUpdateDialog?.let { update ->
            AlertDialog(
                onDismissRequest = { showUpdateDialog = null },
                title = { Text("发现新版本 v${update.versionName}") },
                text = {
                    Column {
                        Text("新版本可用，建议更新：")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(update.releaseNotes, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showUpdateDialog = null
                        scope.launch {
                            if (activity != null) {
                                AppUpdateManager.downloadAndInstall(activity, update)
                            }
                        }
                    }) {
                        Text("立即更新")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = null }) {
                        Text("稍后")
                    }
                }
            )
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

        fun handleLockScreen() {
            // AI要求锁屏 → 标记未放行 → 锁屏
            LockAccessibilityService.getInstance()?.setGranted(false)
            val a11y = LockAccessibilityService.getInstance()
            if (a11y != null && a11y.lockScreen()) {
                markNeedsReset()
            } else {
                Toast.makeText(context, "请先开启无障碍锁屏权限", Toast.LENGTH_LONG).show()
            }
        }

        fun handleExitApp() {
            // AI放行 → 标记已放行 → 返回桌面
            LockAccessibilityService.getInstance()?.setGranted(true)
            markNeedsReset()
            activity?.moveTaskToBack(true)
        }

        fun emergencyUnlock() {
            // 紧急密钥解锁 → 标记已放行 → 返回桌面
            LockAccessibilityService.getInstance()?.setGranted(true)
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
                        onLockScreen = { handleLockScreen() },
                        onExitApp = { handleExitApp() },
                        onSettingsClick = { currentScreen = Screen.Settings },
                        onEmergencyUnlock = { currentScreen = Screen.EmergencyUnlock }
                    )
                    is Screen.Settings -> SettingsScreen(
                        onBack = { currentScreen = Screen.Chat },
                        onEmergencyUnlock = { currentScreen = Screen.EmergencyUnlock },
                        onCheckUpdate = {
                            scope.launch {
                                val update = AppUpdateManager.checkForUpdate()
                                if (update != null) {
                                    showUpdateDialog = update
                                } else {
                                    Toast.makeText(context, "当前已是最新版本 v${BuildConfig.VERSION_NAME}",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
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
