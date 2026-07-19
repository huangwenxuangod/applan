package com.applan.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 守护看板页面
 *
 * Phase 1: 使用模拟数据展示，后续接入Room本地数据库+UsageStatsManager
 * 设计参考：Android Digital Wellbeing + 不做手机控
 * 风格：冷峻数据感，让用户直面自己的"瘾"
 */

// 模拟数据
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

private data class DayHeat(
    val day: String,
    val attempts: Int,
    val isToday: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit
) {
    // 模拟数据
    val statCards = remember {
        listOf(
            StatCard("🔓", "12", "今日逃脱", Color(0xFFFF6B6B)),
            StatCard("⏱", "3.2h", "今日守护", Color(0xFF4ECDC4)),
            StatCard("🔥", "7", "连续天数", Color(0xFFFFA726)),
            StatCard("✅", "83%", "通过率", Color(0xFF66BB6A))
        )
    }

    val topApps = remember {
        listOf(
            AppAttempt("抖音", "🎵", 23, Color(0xFFFF6B6B)),
            AppAttempt("微信", "💬", 15, Color(0xFF4CAF50)),
            AppAttempt("小红书", "📕", 9, Color(0xFFFF5722)),
            AppAttempt("B站", "📺", 7, Color(0xFF2196F3)),
            AppAttempt("微博", "📢", 4, Color(0xFFFF9800))
        )
    }

    val weekData = remember {
        listOf(
            DayHeat("一", 5),
            DayHeat("二", 8),
            DayHeat("三", 3),
            DayHeat("四", 12),
            DayHeat("五", 15),
            DayHeat("六", 18),
            DayHeat("日", 12, isToday = true)
        )
    }

    val maxAttempts = weekData.maxOf { it.attempts }

    Scaffold(
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 核心数据卡片组
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    statCards.forEach { card ->
                        StatCardItem(card = card, modifier = Modifier.weight(1f))
                    }
                }
            }

            // 最近7天趋势
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "最近7天逃脱趋势",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // 简易柱状图
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            weekData.forEach { day ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "${day.attempts}",
                                        fontSize = 10.sp,
                                        color = if (day.isToday) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val barHeight = if (maxAttempts > 0) {
                                        (day.attempts.toFloat() / maxAttempts * 80f).dp
                                    } else 4.dp
                                    Box(
                                        modifier = Modifier
                                            .width(20.dp)
                                            .height(barHeight)
                                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                            .background(
                                                if (day.isToday) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                            )
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = day.day,
                                        fontSize = 11.sp,
                                        color = if (day.isToday) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 最想打开的App排行
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "最想打开的App",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "你的诱惑源排行榜",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        topApps.forEachIndexed { index, app ->
                            AppAttemptRow(
                                rank = index + 1,
                                app = app,
                                maxCount = topApps.first().count
                            )
                            if (index < topApps.size - 1) {
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }

            // 今日时间线
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "今日时间线",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        TimelineItem("14:32", "尝试打开抖音 → 被拦截", isBlocked = true)
                        TimelineDivider()
                        TimelineItem("14:35", "AI放行\"查资料\" → 微信", isGranted = true)
                        TimelineDivider()
                        TimelineItem("15:20", "紧急解锁", isEmergency = true)
                        TimelineDivider()
                        TimelineItem("16:45", "尝试打开小红书 → 被拦截", isBlocked = true)
                    }
                }
            }

            // 底部占位
            item {
                Spacer(modifier = Modifier.height(80.dp))
                Text(
                    "数据将在你使用过程中逐步积累",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StatCardItem(card: StatCard, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = card.color.copy(alpha = 0.12f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(card.emoji, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                card.value,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = card.color
            )
            Text(
                card.label,
                fontSize = 10.sp,
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
        // 排名
        Text(
            text = "$rank",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = app.color,
            modifier = Modifier.width(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(app.emoji, fontSize = 18.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    app.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${app.count}次",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(app.color.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun TimelineItem(time: String, desc: String, isBlocked: Boolean = false, isGranted: Boolean = false, isEmergency: Boolean = false) {
    val (icon, color) = when {
        isBlocked -> "🚫" to Color(0xFFFF6B6B)
        isGranted -> "✅" to Color(0xFF66BB6A)
        isEmergency -> "🔑" to Color(0xFFFFA726)
        else -> "ℹ️" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.width(40.dp))
        Text(icon, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(desc, fontSize = 12.sp, color = color)
    }
}

@Composable
private fun TimelineDivider() {
    Box(
        modifier = Modifier
            .padding(start = 52.dp, top = 4.dp, bottom = 4.dp)
            .width(1.dp)
            .height(12.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
    )
}
