package com.applan.ui

import android.app.Activity
import android.content.Context
import android.util.Log
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
import com.applan.BuildConfig
import com.applan.network.GrantPlanResult
import com.applan.service.BlockOverlay
import com.applan.service.KeepAliveService
import com.applan.service.LockAccessibilityService
import com.applan.ui.chat.ChatScreen
import com.applan.ui.chat.ChatViewModel
import com.applan.ui.dashboard.DashboardScreen
import com.applan.ui.emergency.EmergencyUnlockScreen
import com.applan.ui.onboarding.OnboardingScreen
import com.applan.ui.settings.SettingsScreen
import com.applan.ui.theme.ApplanTheme
import com.applan.util.AccessPlan
import com.applan.util.AppConfig
import com.applan.util.AppPackageResolver
import com.applan.util.AppState
import com.applan.util.AppUpdateManager
import com.applan.util.CrashHandler
import com.applan.util.ViolationRecord
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

sealed class Screen {
    object Onboarding : Screen()
    object Chat : Screen()
    object Settings : Screen()
    object EmergencyUnlock : Screen()
    object Dashboard : Screen()
}

private const val PREFS_NAME = "appplan_prefs"
private const val KEY_ONBOARDED = "has_completed_onboarding"

private val needsResetOnResume = AtomicBoolean(false)

private var chatViewModelRef: ChatViewModel? = null

private val _resetFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val resetFlow = _resetFlow.asSharedFlow()

fun resetChatConversation() {
    chatViewModelRef?.resetConversation()
    _resetFlow.tryEmit(Unit)
    needsResetOnResume.set(false)
}

fun markNeedsReset() {
    needsResetOnResume.set(true)
}

fun consumeNeedsReset(): Boolean {
    return needsResetOnResume.compareAndSet(true, false)
}

fun showTopToast(context: Context, message: String) {
    val toast = Toast.makeText(context, message, Toast.LENGTH_LONG)
    toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 80)
    toast.show()
}

@Composable
fun ApplanApp(context: Context) {
    ApplanTheme {
        val activity = context as? Activity
        val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
        val scope = rememberCoroutineScope()
        var hasOnboarded by remember {
            mutableStateOf(prefs.getBoolean(KEY_ONBOARDED, false))
        }

        val serverUrl = AppConfig.getServerUrl()
        val apiKey = AppConfig.getApiKey()

        val chatViewModel: ChatViewModel = viewModel()

        var currentScreen by remember {
            mutableStateOf<Screen>(if (hasOnboarded) Screen.Chat else Screen.Onboarding)
        }
        var showUpdateDialog by remember { mutableStateOf<AppUpdateManager.UpdateInfo?>(null) }
        var crashReport by remember { mutableStateOf<String?>(null) }

        // 初始化ApplanClient配置（只执行一次）
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

        // 严格模式权限撤销检测：如果KeepAliveService检测到权限被关闭，强制跳回引导页
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(3000)
                if (AppState.isPermissionRevoked()) {
                    AppState.clearPermissionRevoked()
                    hasOnboarded = false
                    currentScreen = Screen.Onboarding
                    showTopToast(context, "⚠️ 检测到关键权限被关闭，请重新开启")
                }
            }
        }

        // 检查上次崩溃记录
        LaunchedEffect(Unit) {
            if (CrashHandler.hasUnreadCrashes()) {
                crashReport = CrashHandler.getLatestCrash()
            }
        }

        LaunchedEffect(Unit) {
            resetFlow.collect {
                currentScreen = Screen.Chat
            }
        }

        // 崩溃报告对话框
        crashReport?.let { report ->
            AlertDialog(
                onDismissRequest = {
                    CrashHandler.clearCrashes()
                    crashReport = null
                },
                title = { Text("⚠️ applan 上次运行崩溃了", fontSize = 16.sp) },
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
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("appplan-crash", report))
                        showTopToast(context, "已复制到剪贴板")
                        CrashHandler.clearCrashes()
                        crashReport = null
                    }) { Text("复制") }
                }
            )
        }

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

        BackHandler(enabled = true) {
            // 完全拦截系统返回手势：
            // - Settings/EmergencyUnlock → 界面内导航回到Chat
            // - Chat/Onboarding → 什么都不做，彻底吞掉返回事件，不退出
            when (currentScreen) {
                is Screen.Settings -> {
                    AppState.endSettingsSession()
                    currentScreen = Screen.Chat
                }
                is Screen.EmergencyUnlock -> {
                    currentScreen = Screen.Chat
                }
                is Screen.Dashboard -> {
                    currentScreen = Screen.Chat
                }
                is Screen.Chat -> {
                    // 聊天页：完全拦截，不做任何操作，不退出App
                    // 用户只能通过AI放行或紧急解锁才能离开
                }
                is Screen.Onboarding -> {
                    // 引导页：完全拦截，必须完成权限配置才能进入
                }
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
            // AI放行：设置持久化标志，所有拦截/保活逻辑都检查这个标志
            AppConfig.setExitGranted(true)
            AppState.grantByAi()
            // 立即隐藏遮罩，确保用户回到桌面时看不到遮罩
            BlockOverlay.hide()
            markNeedsReset()
            activity?.moveTaskToBack(true)
        }

        fun emergencyUnlock() {
            // 紧急解锁：同样设置持久化放行标志
            AppConfig.setExitGranted(true)
            AppState.grantByEmergency()
            // 立即隐藏遮罩
            BlockOverlay.hide()
            markNeedsReset()
            activity?.moveTaskToBack(true)
            currentScreen = Screen.Chat
        }

        fun goToSettings() {
            AppState.startSettingsSession()
            currentScreen = Screen.Settings
        }

        fun handleViolation(record: ViolationRecord) {
            Log.d("ApplanApp", "Violation detected: ${record.appName} (${record.packageName})")
            if (AppConfig.isExitGranted()) return

            // Plan Mode开启时：违规直接狠狠重启锁定，不拉回Chat对话
            if (AppConfig.isPlanModeEnabled()) {
                Log.d("ApplanApp", "Plan Mode: hard reset on violation")
                AppState.resetGrant()
                AppConfig.setExitGranted(false)
                BlockOverlay.show(context)
                val a11y = LockAccessibilityService.getInstance()
                a11y?.lockScreen()
                markNeedsReset()
                KeepAliveService.bringToForeground(context, "plan_violation_${record.packageName}")
                showTopToast(context, "计划违规：${record.appName}，已重新锁定")
                return
            }

            // 普通模式：拉回Chat页面对话
            markNeedsReset()
            currentScreen = Screen.Chat
            KeepAliveService.bringToForeground(context, "violation_${record.packageName}")
            showTopToast(context, "检测到偏离计划：${record.appName}")
        }

        fun handleGrantPlan(result: GrantPlanResult) {
            Log.d("ApplanApp", "Grant plan: ${result.planDescription}, apps=${result.appNames}")
            if (result.resolvedPackages.isEmpty()) {
                showTopToast(context, "无法识别计划中的App：${result.unresolvedApps.joinToString(", ")}")
                return
            }
            if (result.unresolvedApps.isNotEmpty()) {
                showTopToast(context, "部分App未识别：${result.unresolvedApps.joinToString(", ")}，仅放行已识别的App")
            }
            val timeoutMs = result.timeoutMinutes.coerceIn(1, 60) * 60 * 1000L
            val plan = AccessPlan(
                allowedPackages = result.resolvedPackages.toMutableSet(),
                allowedAppNames = result.appNames.toMutableList(),
                planDescription = result.planDescription,
                timeoutAt = System.currentTimeMillis() + timeoutMs
            )
            AppState.grantByPlan(plan)
            BlockOverlay.hide()
            markNeedsReset()
            showTopToast(context, "计划放行：${result.planDescription}，${result.timeoutMinutes}分钟")
        }

        DisposableEffect(Unit) {
            chatViewModelRef = chatViewModel
            AppState.onViolationDetected = { record ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    handleViolation(record)
                }
            }
            onDispose {
                chatViewModelRef = null
                AppState.onViolationDetected = null
            }
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
                        onDashboardClick = { currentScreen = Screen.Dashboard },
                        onEmergencyUnlock = { currentScreen = Screen.EmergencyUnlock },
                        onGrantPlan = { result -> handleGrantPlan(result) }
                    )
                    is Screen.Settings -> SettingsScreen(
                        onBack = {
                            AppState.endSettingsSession()
                            currentScreen = Screen.Chat
                        },
                        onBeforeOpenSettings = {
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
                        }
                    )
                    is Screen.EmergencyUnlock -> EmergencyUnlockScreen(
                        onUnlockSuccess = { emergencyUnlock() },
                        onBack = { currentScreen = Screen.Chat }
                    )
                    is Screen.Dashboard -> DashboardScreen(
                        onBack = { currentScreen = Screen.Chat }
                    )
                }
            }
        }
    }
}
