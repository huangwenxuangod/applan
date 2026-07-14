package com.lockai.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lockai.BuildConfig
import com.lockai.network.HermesClient
import com.lockai.service.KeepAliveService
import com.lockai.service.LockAccessibilityService
import com.lockai.ui.chat.ChatScreen
import com.lockai.ui.chat.ChatViewModel
import com.lockai.ui.emergency.EmergencyUnlockScreen
import com.lockai.ui.onboarding.OnboardingScreen
import com.lockai.ui.settings.SettingsScreen
import com.lockai.ui.theme.LockAITheme
import com.lockai.util.AppConfig
import com.lockai.util.AppState
import com.lockai.util.AppUpdateManager
import com.lockai.util.CrashHandler
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

/**
 * 显示顶部Toast
 */
fun showTopToast(context: Context, message: String) {
    val toast = Toast.makeText(context, message, Toast.LENGTH_LONG)
    toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 80)
    toast.show()
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

        // 从AppConfig加载服务器配置
        val serverUrl = AppConfig.getServerUrl()
        val apiKey = AppConfig.getApiKey()

        val chatViewModel: ChatViewModel = viewModel()

        // 初始化HermesClient配置
        LaunchedEffect(Unit) {
            chatViewModel.updateConfig(serverUrl, apiKey)
        }

        var currentScreen by remember {
            mutableStateOf<Screen>(if (hasOnboarded) Screen.Chat else Screen.Onboarding)
        }
        var showUpdateDialog by remember { mutableStateOf<AppUpdateManager.UpdateInfo?>(null) }
        var crashReport by remember { mutableStateOf<String?>(null) }

        // 检查上次崩溃记录
        LaunchedEffect(Unit) {
            if (CrashHandler.hasUnreadCrashes()) {
                crashReport = CrashHandler.getLatestCrash()
            }
        }

        // 监听重置事件
        LaunchedEffect(Unit) {
            resetFlow.collect {
                currentScreen = Screen.Chat
            }
        }

        // 监听错误事件 → 顶部Toast
        LaunchedEffect(Unit) {
            chatViewModel.errorEvent.collect { error ->
                if (error != null) {
                    showTopToast(context, error)
                    chatViewModel.dismissError()
                }
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
                scope.launch {
                    val update = AppUpdateManager.checkForUpdate()
                    if (update != null) showUpdateDialog = update
                }
            }
        }

        // 崩溃报告对话框
        crashReport?.let { report ->
            AlertDialog(
                onDismissRequest = {
                    CrashHandler.clearCrashes()
                    crashReport = null
                },
                title = { Text("⚠️ AppPlan 上次运行崩溃了", fontSize = 16.sp) },
                text = {
                    Column(modifier = Modifier.heightIn(max = 280.dp)) {
                        Text("错误信息已保存，可复制反馈给开发者：", fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                report.take(3000),
                                modifier = Modifier.padding(8.dp),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 14.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        CrashHandler.clearCrashes()
                        crashReport = null
                    }) { Text("知道了") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("lockai-crash", report))
                        showTopToast(context, "已复制到剪贴板")
                        CrashHandler.clearCrashes()
                        crashReport = null
                    }) { Text("复制") }
                }
            )
        }

        // 更新对话框
        showUpdateDialog?.let { update ->
            AlertDialog(
                onDismissRequest = { showUpdateDialog = null },
                title = { Text("发现新版本 v${update.versionName}") },
                text = {
                    Column {
                        Text("新版本可用：")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(update.releaseNotes, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
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
                    }) { Text("立即更新") }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = null }) { Text("稍后") }
                }
            )
        }

        BackHandler(enabled = currentScreen == Screen.Chat || currentScreen == Screen.Onboarding) {
            when (currentScreen) {
                is Screen.Chat -> {
                    AppState.grantByAi()
                    markNeedsReset()
                    activity?.moveTaskToBack(true)
                }
                is Screen.Onboarding -> {}
                else -> {}
            }
        }

        fun completeOnboarding() {
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
            hasOnboarded = true
            AppState.resetGrant()
            KeepAliveService.start(context)
            currentScreen = Screen.Chat
        }

        fun handleLockScreen() {
            AppState.resetGrant()
            val a11y = LockAccessibilityService.getInstance()
            if (a11y != null && a11y.lockScreen()) {
                markNeedsReset()
            } else {
                showTopToast(context, "请先开启无障碍锁屏权限")
                // 跳设置前开启宽限期
                AppState.startSettingsSession()
                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }

        fun handleExitApp() {
            AppState.grantByAi()
            markNeedsReset()
            activity?.moveTaskToBack(true)
        }

        fun emergencyUnlock() {
            AppState.grantByEmergency()
            markNeedsReset()
            activity?.moveTaskToBack(true)
            currentScreen = Screen.Chat
        }

        fun goToSettings() {
            // 跳设置前开启宽限期，无障碍服务不拦截
            AppState.startSettingsSession()
            currentScreen = Screen.Settings
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
                        onSettingsClick = { goToSettings() },
                        onEmergencyUnlock = { currentScreen = Screen.EmergencyUnlock }
                    )
                    is Screen.Settings -> SettingsScreen(
                        onBack = {
                            AppState.endSettingsSession()
                            currentScreen = Screen.Chat
                        },
                        onBeforeOpenSettings = {
                            // 跳系统设置前开启宽限期
                            AppState.startSettingsSession()
                        },
                        onEmergencyUnlock = { currentScreen = Screen.EmergencyUnlock },
                        onCheckUpdate = {
                            scope.launch {
                                val update = AppUpdateManager.checkForUpdate()
                                if (update != null) {
                                    showUpdateDialog = update
                                } else {
                                    showTopToast(context, "当前已是最新版本 v${BuildConfig.VERSION_NAME}")
                                }
                            }
                        },
                        onConfigSaved = { url, key ->
                            chatViewModel.updateConfig(url, key)
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
