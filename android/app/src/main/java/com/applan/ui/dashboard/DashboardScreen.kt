package com.applan.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.applan.ui.common.swipeToBack
import com.applan.network.ApplanClient
import com.applan.util.AppConfig
import com.applan.util.AppPackageResolver
import com.applan.util.EventAnalytics
import com.applan.util.PolicyEventStore
import com.applan.util.PolicyRepository
import com.applan.util.AiPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlinx.coroutines.delay

/**
 * 守护看板页面
 *
 * 设计原则：一屏看完，只放最关键的数据。
 * Phase 1: 使用模拟数据，后续接入Room本地数据库+UsageStatsManager
 */

private data class StatCard(
    val emoji: String,
    val value: String,
    val label: String,
    val color: Color
)

private data class AppAttempt(
    val name: String,
    val emoji: String,
    val count: Int,
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    onEndPlan: () -> Unit
) {
    val context = LocalContext.current
    var activePlan by remember { mutableStateOf(PolicyRepository(context).getPlan()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmEndPlan by remember { mutableStateOf(false) }
    LaunchedEffect(activePlan?.expiresAt) {
        while (activePlan != null) {
            now = System.currentTimeMillis()
            if (activePlan!!.expiresAt <= now) {
                PolicyRepository(context).clearPlan()
                PolicyEventStore(context).record("plan_expired")
                activePlan = null
            }
            delay(1_000)
        }
    }
    val events = remember { PolicyEventStore(context).getAll() }
    LaunchedEffect(events) {
        withContext(Dispatchers.IO) {
            ApplanClient(context).apply { updateConfig(AppConfig.getServerUrl(), AppConfig.getApiKey()) }.syncEvents(events)
        }
    }
    val calendar = remember { Calendar.getInstance() }
    val startOfDay = remember {
        (calendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val summary = remember(events, startOfDay) { EventAnalytics.summarize(events, startOfDay, startOfDay + 86_400_000) }
    val statCards = remember(summary) {
        listOf(
            StatCard("🛡", "${summary.blockedCount}", "今日阻断", Color(0xFFFF6B6B)),
            StatCard("⏱", "${summary.focusMinutes}m", "Plan 专注", Color(0xFF4ECDC4)),
            StatCard("📱", "${summary.topApps.size}", "诱惑应用", Color(0xFFFFA726)),
            StatCard("✅", "${summary.planStartedCount}", "今日计划", Color(0xFF66BB6A))
        )
    }

    val colors = listOf(Color(0xFFFF6B6B), Color(0xFF4CAF50), Color(0xFFFF5722), Color(0xFF2196F3), Color(0xFFFF9800))
    val topApps = remember(summary) {
        summary.topApps.take(5).mapIndexed { index, app ->
            AppAttempt(AppPackageResolver.getAppName(context, app.packageName), "📱", app.count, colors[index])
        }
    }

    val weekData = remember(events, startOfDay) {
        List(7) { index ->
            val dayStart = startOfDay - (6 - index) * 86_400_000L
            events.count { it.type == "app_blocked" && it.occurredAt in dayStart until (dayStart + 86_400_000L) }
        }
    }
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    val maxAttempts = weekData.maxOrNull() ?: 1

    Scaffold(
        modifier = Modifier.swipeToBack(onBack = onBack),
        topBar = {
            TopAppBar(
                title = {
                    Text("守护看板", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            activePlan?.let { plan ->
                DashboardPlanPanel(plan, now, context, onEnd = { confirmEndPlan = true })
            } ?: Text(
                "当前没有计划，回到对话开始一个计划",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // === 核心数据卡片组 ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                statCards.forEach { card ->
                    StatCardItem(card = card, modifier = Modifier.weight(1f))
                }
            }

            // === 最近7天趋势（紧凑型柱状图） ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        "7日逃脱趋势",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        weekData.forEachIndexed { index, count ->
                            val isToday = index == 6
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "$count",
                                    fontSize = 9.sp,
                                    color = if (isToday) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val barHeight = if (maxAttempts > 0) {
                                    (count.toFloat() / maxAttempts * 50f).dp
                                } else 4.dp
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(barHeight)
                                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                        .background(
                                            if (isToday) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                        )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = weekLabels[index],
                                    fontSize = 10.sp,
                                    color = if (isToday) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // === 最想打开的App ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        "诱惑源排行",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    topApps.forEachIndexed { index, app ->
                        AppAttemptRow(
                            rank = index + 1,
                            app = app,
                            maxCount = topApps.maxOfOrNull { it.count } ?: 1
                        )
                        if (index < topApps.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            // 底部提示
            Text(
                "数据将在使用过程中逐步积累",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (confirmEndPlan) {
        AlertDialog(
            onDismissRequest = { confirmEndPlan = false },
            title = { Text("结束当前计划？") },
            text = { Text("结束后将恢复普通状态。") },
            confirmButton = { TextButton(onClick = { onEndPlan(); activePlan = null; confirmEndPlan = false }) { Text("结束") } },
            dismissButton = { TextButton(onClick = { confirmEndPlan = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun DashboardPlanPanel(plan: AiPlan, now: Long, context: android.content.Context, onEnd: () -> Unit) {
    val seconds = ((plan.expiresAt - now).coerceAtLeast(0) / 1_000)
    val names = plan.allowedPackages.take(4).joinToString("、") { AppPackageResolver.getAppName(context, it) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text("当前计划", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(plan.purpose, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("剩余 %02d:%02d".format(seconds / 60, seconds % 60), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text("允许：$names", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onEnd, contentPadding = PaddingValues(0.dp)) { Text("结束计划") }
        }
    }
}

@Composable
private fun StatCardItem(card: StatCard, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = card.color.copy(alpha = 0.12f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(card.emoji, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                card.value,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = card.color
            )
            Text(
                card.label,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AppAttemptRow(rank: Int, app: AppAttempt, maxCount: Int) {
    val fraction = if (maxCount > 0) app.count.toFloat() / maxCount else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "$rank",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = app.color,
            modifier = Modifier.width(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(app.emoji, fontSize = 16.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    app.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${app.count}次",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.05f))
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(app.color.copy(alpha = 0.6f))
            )
        }
    }
}
