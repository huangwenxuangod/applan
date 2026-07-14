package com.lockai.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockai.R
import com.lockai.util.AppConfig
import com.lockai.util.AppState
import com.lockai.util.AppUpdateManager
import com.lockai.util.AutoStartHelper
import com.lockai.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onBeforeOpenSettings: () -> Unit = {},
    onEmergencyUnlock: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onConfigSaved: (serverUrl: String, apiKey: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableStateOf(0) }
    val versionName = remember { AppUpdateManager.getCurrentVersionName(context) }

    val batteryGranted by remember(refreshKey) {
        derivedStateOf { PermissionHelper.isIgnoringBatteryOptimizations(context) }
    }
    val accessibilityGranted by remember(refreshKey) {
        derivedStateOf { PermissionHelper.isAccessibilityEnabled(context) }
    }
    val notificationGranted by remember(refreshKey) {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        }
    }
    val overlayGranted by remember(refreshKey) {
        derivedStateOf { PermissionHelper.canDrawOverlays(context) }
    }
    val isDefaultLauncher by remember(refreshKey) {
        derivedStateOf { PermissionHelper.isDefaultLauncher(context) }
    }

    // 服务器配置状态
    var serverUrlInput by remember { mutableStateOf(AppConfig.getServerUrl()) }
    var apiKeyInput by remember { mutableStateOf(AppConfig.getApiKey()) }
    var showConfigSaved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(800)
            refreshKey++
        }
    }

    // 跳系统设置的安全包装：先开宽限期再跳转
    fun openSettings(action: () -> Unit) {
        onBeforeOpenSettings()
        AppState.startSettingsSession()
        action()
    }

    fun saveConfig() {
        AppConfig.saveServerUrl(serverUrlInput)
        AppConfig.saveApiKey(apiKeyInput)
        onConfigSaved(serverUrlInput.trimEnd('/'), apiKeyInput.trim())
        showConfigSaved = true
        scope.launch {
            delay(1500)
            showConfigSaved = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            if (showConfigSaved) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Text("配置已保存 ✓")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 服务器配置
            item {
                SectionTitle("服务器配置")
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            label = { Text("Hermes Agent 地址") },
                            placeholder = { Text("http://192.168.x.x:8787") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("API Key（可选）") },
                            placeholder = { Text("留空则不使用") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_lock),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    serverUrlInput = AppConfig.getServerUrl()
                                    apiKeyInput = AppConfig.getApiKey()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("重置")
                            }
                            Button(
                                onClick = { saveConfig() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("保存")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "提示：同一WiFi下填电脑局域网IP，如 http://192.168.1.100:8787",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // 必要权限
            item {
                SectionTitle("权限管理")
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        ToggleItem(
                            title = "自启动",
                            desc = "开机/解锁自动启动，需在厂商设置开启（在列表中找「AppPlan」）",
                            checked = false,
                            onCheckedChange = {
                                openSettings { AutoStartHelper.jumpToAutoStartSetting(context) }
                            },
                            showSwitch = false
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "电池优化白名单",
                            desc = "防止系统杀死后台服务",
                            checked = batteryGranted,
                            onCheckedChange = {
                                openSettings { PermissionHelper.requestBatteryOptimization(context) }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "锁屏服务（无障碍）",
                            desc = "AI判断后执行锁屏+拦截Home键",
                            checked = accessibilityGranted,
                            onCheckedChange = {
                                openSettings { PermissionHelper.jumpToAccessibilitySettings(context) }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "悬浮窗权限",
                            desc = "在锁屏上显示界面",
                            checked = overlayGranted,
                            onCheckedChange = {
                                openSettings { PermissionHelper.jumpToOverlaySettings(context) }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "通知",
                            desc = "显示运行状态通知",
                            checked = notificationGranted,
                            onCheckedChange = {
                                openSettings { PermissionHelper.jumpToNotificationSettings(context) }
                            }
                        )
                    }
                }
            }

            // 推荐设置
            item {
                SectionTitle("推荐设置")
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        NavItem(
                            title = "设为默认桌面",
                            desc = if (isDefaultLauncher) "已设为默认桌面 ✓" else "按Home键自动回到AppPlan，最稳定防杀",
                            onClick = {
                                openSettings { PermissionHelper.requestDefaultLauncher(context) }
                            }
                        )
                    }
                }
            }

            // 紧急通道
            item {
                SectionTitle("紧急通道")
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    NavItem(
                        title = "紧急解锁（8位密钥）",
                        desc = "AI无法判断时的硬保底，每次随机生成",
                        onClick = onEmergencyUnlock,
                        titleColor = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 关于
            item {
                SectionTitle("关于")
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        NavItem(
                            title = "检查更新",
                            desc = "当前版本 v$versionName",
                            onClick = onCheckUpdate
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "AppPlan",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "手机第一道防线。\nAI守门人，专治玩手机找借口。",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
fun ToggleItem(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showSwitch: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                desc,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        if (showSwitch) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                )
            )
        } else {
            Text(
                "去设置 ›",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun NavItem(
    title: String,
    desc: String,
    onClick: () -> Unit,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                desc,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "›",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
