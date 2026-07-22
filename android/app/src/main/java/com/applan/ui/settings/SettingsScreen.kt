package com.applan.ui.settings

import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.applan.R
import com.applan.service.ScheduleBoundaryReceiver
import com.applan.util.AppConfig
import com.applan.util.AppState
import com.applan.util.AppUpdateManager
import com.applan.util.AutoStartHelper
import com.applan.util.EmergencyKeyGenerator
import com.applan.util.PermissionHelper
import com.applan.util.PolicyRepository
import com.applan.util.TimeProfile
import com.applan.ui.common.swipeToBack
import kotlinx.coroutines.launch
import java.util.Locale

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
    val lifecycleOwner = LocalLifecycleOwner.current

    // 只在生命周期RESUME时刷新权限状态，移除800ms死循环轮询
    var permissionVersion by remember { mutableStateOf(0) }
    val versionName = remember { AppUpdateManager.getCurrentVersionName(context) }

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

    val batteryGranted by remember(permissionVersion) {
        derivedStateOf { PermissionHelper.isIgnoringBatteryOptimizations(context) }
    }
    val accessibilityGranted by remember(permissionVersion) {
        derivedStateOf { PermissionHelper.isAccessibilityEnabled(context) }
    }
    val notificationGranted by remember(permissionVersion) {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        }
    }
    val overlayGranted by remember(permissionVersion) {
        derivedStateOf { PermissionHelper.canDrawOverlays(context) }
    }
    val isDefaultLauncher by remember(permissionVersion) {
        derivedStateOf { PermissionHelper.isDefaultLauncher(context) }
    }

    // 严格模式状态
    var strictModeEnabled by remember(permissionVersion) {
        mutableStateOf(AppConfig.isStrictModeEnabled())
    }

    // 计划模式状态（Plan Mode）
    var planModeEnabled by remember {
        mutableStateOf(AppConfig.isPlanModeEnabled())
    }

    var dailyProfile by remember {
        mutableStateOf(PolicyRepository(context).getProfiles().firstOrNull { it.id == DAILY_PROFILE_ID })
    }

    fun saveDailyProfile(profile: TimeProfile?) {
        val repository = PolicyRepository(context)
        val profiles = repository.getProfiles().filterNot { it.id == DAILY_PROFILE_ID }.toMutableList()
        if (profile != null) profiles.add(profile)
        repository.saveProfiles(profiles)
        ScheduleBoundaryReceiver.scheduleNext(context)
        dailyProfile = profile
    }

    fun pickTime(currentMinute: Int, onSelected: (Int) -> Unit) {
        TimePickerDialog(
            context,
            { _, hour, minute -> onSelected(hour * 60 + minute) },
            currentMinute / 60,
            currentMinute % 60,
            true
        ).show()
    }

    // 必需权限检查 - 用于自动启用严格模式
    val usageStatsGranted by remember(permissionVersion) {
        derivedStateOf { PermissionHelper.isUsageStatsGranted(context) }
    }
    val backgroundStartGranted by remember(permissionVersion) {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PermissionHelper.isBackgroundStartEnabled(context)
            } else true
        }
    }
    val batteryWhitelisted by remember(permissionVersion) {
        derivedStateOf { PermissionHelper.isBatteryWhitelisted(context) }
    }

    // 自动启用严格模式：当严格模式未启用但所有必需权限都已开启时
    LaunchedEffect(
        accessibilityGranted,
        overlayGranted,
        usageStatsGranted,
        backgroundStartGranted,
        batteryWhitelisted
    ) {
        if (!strictModeEnabled) {
            val allRequiredGranted = accessibilityGranted &&
                overlayGranted &&
                usageStatsGranted &&
                backgroundStartGranted &&
                batteryWhitelisted
            if (allRequiredGranted) {
                AppConfig.enableStrictMode()
                strictModeEnabled = true
            }
        }
    }

    // 跳系统设置的安全包装：先开宽限期再跳转
    fun openSettings(action: () -> Unit) {
        onBeforeOpenSettings()
        AppState.startSettingsSession()
        action()
    }

    Scaffold(
        modifier = Modifier.swipeToBack(onBack = onBack),
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
            // 严格模式警告横幅
            if (strictModeEnabled) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE53935))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "严格模式已启用 - 权限配置已锁定，无法关闭",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle("每日时间段")
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        ToggleItem(
                            title = "按时间段阻断",
                            desc = "时间段内被动拦截全部非系统应用，不自动打开 applan",
                            checked = dailyProfile != null,
                            onCheckedChange = { enabled ->
                                saveDailyProfile(
                                    if (enabled) TimeProfile(
                                        id = DAILY_PROFILE_ID,
                                        weekdays = (1..7).toSet(),
                                        startMinute = 9 * 60,
                                        endMinute = 17 * 60,
                                        allowedPackages = emptySet()
                                    ) else null
                                )
                            }
                        )
                        dailyProfile?.let { profile ->
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            NavItem(
                                title = "开始时间",
                                desc = formatMinute(profile.startMinute),
                                onClick = {
                                    pickTime(profile.startMinute) { minute ->
                                        saveDailyProfile(profile.copy(startMinute = minute))
                                    }
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            NavItem(
                                title = "结束时间",
                                desc = formatMinute(profile.endMinute),
                                onClick = {
                                    pickTime(profile.endMinute) { minute ->
                                        saveDailyProfile(profile.copy(endMinute = minute))
                                    }
                                }
                            )
                        }
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
                            desc = "开机/解锁自动启动，需在厂商设置开启（在列表中找「applan」）",
                            checked = false,
                            onCheckedChange = {
                                openSettings { AutoStartHelper.jumpToAutoStartSetting(context) }
                            },
                            showSwitch = false,
                            enabled = !strictModeEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "电池优化白名单",
                            desc = "防止系统杀死后台服务",
                            checked = batteryGranted,
                            onCheckedChange = {
                                openSettings { PermissionHelper.requestBatteryOptimization(context) }
                            },
                            enabled = !strictModeEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "锁屏服务（无障碍）",
                            desc = "AI判断后执行锁屏+拦截Home键",
                            checked = accessibilityGranted,
                            onCheckedChange = {
                                openSettings { PermissionHelper.jumpToAccessibilitySettings(context) }
                            },
                            enabled = !strictModeEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "悬浮窗权限",
                            desc = "在锁屏上显示界面",
                            checked = overlayGranted,
                            onCheckedChange = {
                                openSettings { PermissionHelper.jumpToOverlaySettings(context) }
                            },
                            enabled = !strictModeEnabled
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ToggleItem(
                            title = "通知",
                            desc = "显示运行状态通知",
                            checked = notificationGranted,
                            onCheckedChange = {
                                openSettings { PermissionHelper.jumpToNotificationSettings(context) }
                            },
                            enabled = !strictModeEnabled
                        )
                    }
                }
            }

            // 守护模式
            item {
                SectionTitle("守护模式")
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        ToggleItem(
                            title = "计划模式 (Plan Mode)",
                            desc = "放行后持续监控App使用，偏离计划直接锁定。说去飞书却刷抖音？不存在的。",
                            checked = planModeEnabled,
                            onCheckedChange = { enabled ->
                                AppConfig.setPlanModeEnabled(enabled)
                                planModeEnabled = enabled
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
                            desc = if (isDefaultLauncher) "已设为默认桌面 ✓" else "按Home键自动回到applan，最稳定防杀",
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
                        title = "紧急解锁（${EmergencyKeyGenerator.keyLength()}位密钥）",
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
                                "applan",
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

private const val DAILY_PROFILE_ID = "daily-global-block"

private fun formatMinute(value: Int): String = String.format(Locale.US, "%02d:%02d", value / 60, value % 60)

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
    showSwitch: Boolean = true,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                desc,
                fontSize = 13.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        if (showSwitch) {
            if (!enabled && checked) {
                // 严格模式：已开启的开关旁显示锁图标
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "已锁定",
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Switch(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    disabledCheckedThumbColor = Color.Gray,
                    disabledCheckedTrackColor = Color.LightGray,
                    disabledUncheckedThumbColor = Color.Gray,
                    disabledUncheckedTrackColor = Color.LightGray
                )
            )
        } else {
            Text(
                if (enabled) "去设置 ›" else "已锁定",
                fontSize = 13.sp,
                color = if (enabled) MaterialTheme.colorScheme.primary else Color.Gray,
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
