package com.applan.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.applan.util.AppState
import com.applan.util.AutoStartHelper
import com.applan.util.PermissionHelper

data class PermissionItem(
    val id: String,
    val title: String,
    val desc: String,
    val icon: String,
    val isGranted: () -> Boolean,
    val request: () -> Unit,
    val canDetect: Boolean = true // 是否能通过代码检测权限状态
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 用一个版本号触发刷新，只在生命周期RESUME时更新
    var permissionVersion by remember { mutableStateOf(0) }
    var autostartConfirmed by remember { mutableStateOf(false) }

    // 监听生命周期 - 只有从设置页面返回时(onResume)才刷新权限状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionVersion++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun openSettings(action: () -> Unit) {
        AppState.startSettingsSession()
        action()
    }

    // 只在permissionVersion变化时重新计算权限列表
    val permissions = remember(permissionVersion, autostartConfirmed) {
        listOf(
            PermissionItem(
                id = "notification",
                title = "通知权限",
                desc = "显示后台服务运行通知，必须开启",
                icon = "🔔",
                isGranted = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                    } else true
                },
                request = { openSettings { PermissionHelper.jumpToNotificationSettings(context) } }
            ),
            PermissionItem(
                id = "battery",
                title = "电池优化白名单",
                desc = "防止系统杀死applan后台服务，必须开启",
                icon = "🔋",
                isGranted = { PermissionHelper.isIgnoringBatteryOptimizations(context) },
                request = { openSettings { PermissionHelper.requestBatteryOptimization(context) } }
            ),
            PermissionItem(
                id = "accessibility",
                title = "锁屏服务（无障碍）",
                desc = "AI判断你在找借口时执行锁屏，必须开启",
                icon = "🔒",
                isGranted = { PermissionHelper.isAccessibilityEnabled(context) },
                request = { openSettings { PermissionHelper.jumpToAccessibilitySettings(context) } }
            ),
            PermissionItem(
                id = "autostart",
                title = "自启动权限",
                desc = "开机自启+解锁弹出，需要在厂商设置中手动开启后勾选确认",
                icon = "🚀",
                isGranted = { autostartConfirmed },
                request = { openSettings { AutoStartHelper.jumpToAutoStartSetting(context) } },
                canDetect = false
            ),
            PermissionItem(
                id = "overlay",
                title = "悬浮窗权限",
                desc = "在锁屏上显示界面，推荐开启",
                icon = "🪟",
                isGranted = { PermissionHelper.canDrawOverlays(context) },
                request = { openSettings { PermissionHelper.jumpToOverlaySettings(context) } }
            ),
            PermissionItem(
                id = "launcher",
                title = "设为默认桌面（推荐）",
                desc = "按Home键自动回到applan，最稳定可靠",
                icon = "🏠",
                isGranted = { PermissionHelper.isDefaultLauncher(context) },
                request = { openSettings { PermissionHelper.requestDefaultLauncher(context) } }
            )
        )
    }

    // 必需权限：通知、电池、无障碍、自启动确认
    val requiredGranted = remember(permissionVersion, autostartConfirmed) {
        permissions.filter { it.id != "launcher" && it.id != "overlay" }.all { it.isGranted() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "🔒 applan",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "手机第一道防线",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "请依次开启以下权限，所有必要权限必须开启才能正常使用。自启动权限需要在厂商设置中手动开启，开启后勾选\"我已开启\"确认。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = permissions,
                    key = { it.id }
                ) { perm ->
                    val granted = perm.isGranted()
                    val isRequired = perm.id != "launcher" && perm.id != "overlay"
                    PermissionRow(
                        item = perm,
                        granted = granted,
                        isRequired = isRequired,
                        onClick = {
                            perm.request()
                            // 点击后延迟一点刷新，给系统设置时间响应
                            permissionVersion++
                        },
                        onAutostartConfirm = if (perm.id == "autostart") {
                            {
                                autostartConfirmed = !autostartConfirmed
                                permissionVersion++
                            }
                        } else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onAllGranted,
                enabled = requiredGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    if (requiredGranted) "开始使用" else "请先开启所有必要权限",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun PermissionRow(
    item: PermissionItem,
    granted: Boolean,
    isRequired: Boolean,
    onClick: () -> Unit,
    onAutostartConfirm: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = if (granted)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else
            MaterialTheme.colorScheme.surface,
        tonalElevation = if (granted) 0.dp else 1.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                item.icon,
                fontSize = 22.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isRequired) "必需" else "推荐",
                        fontSize = 10.sp,
                        color = if (isRequired) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    item.desc,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 14.sp
                )
                // 自启动权限的手动确认复选框
                if (onAutostartConfirm != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { onAutostartConfirm() }
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = granted,
                            onCheckedChange = { onAutostartConfirm() },
                            modifier = Modifier.size(18.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "我已在设置中开启自启动",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            if (granted) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        "✓",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isRequired)
                        MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                    else
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                ) {
                    Text(
                        "去开启",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isRequired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
