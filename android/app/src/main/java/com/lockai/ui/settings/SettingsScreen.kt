package com.lockai.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockai.R
import com.lockai.util.AppUpdateManager
import com.lockai.util.AutoStartHelper
import com.lockai.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEmergencyUnlock: () -> Unit = {},
    onCheckUpdate: () -> Unit = {}
) {
    val context = LocalContext.current

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

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(800)
            refreshKey++
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
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
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
                            desc = "开机/解锁自动启动，需在厂商设置开启",
                            checked = false,
                            onCheckedChange = { AutoStartHelper.jumpToAutoStartSetting(context) },
                            showSwitch = false
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "电池优化白名单",
                            desc = "防止系统杀死后台服务",
                            checked = batteryGranted,
                            onCheckedChange = { PermissionHelper.requestBatteryOptimization(context) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "锁屏服务（无障碍）",
                            desc = "AI判断后执行锁屏+拦截Home键",
                            checked = accessibilityGranted,
                            onCheckedChange = { PermissionHelper.jumpToAccessibilitySettings(context) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "悬浮窗权限",
                            desc = "在锁屏上显示界面",
                            checked = overlayGranted,
                            onCheckedChange = { PermissionHelper.jumpToOverlaySettings(context) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "通知",
                            desc = "显示运行状态通知",
                            checked = notificationGranted,
                            onCheckedChange = { PermissionHelper.jumpToNotificationSettings(context) }
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
                            desc = if (isDefaultLauncher) "已设为默认桌面 ✓" else "按Home键自动回到LockAI，最稳定防杀",
                            onClick = { PermissionHelper.requestDefaultLauncher(context) }
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
                        title = "紧急解锁（64位密钥）",
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
                                "LockAI",
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
