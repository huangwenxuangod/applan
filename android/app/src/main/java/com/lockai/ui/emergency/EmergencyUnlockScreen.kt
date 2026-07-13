package com.lockai.ui.emergency

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockai.util.EmergencyKeyGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE_UPPER = 0
private const val PAGE_LOWER = 1
private const val PAGE_NUMBER = 2
private const val PAGE_SYMBOL = 3

private val PAGES = mapOf(
    PAGE_UPPER to "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toList().map { it.toString() },
    PAGE_LOWER to "abcdefghijklmnopqrstuvwxyz".toList().map { it.toString() },
    PAGE_NUMBER to "0123456789".toList().map { it.toString() },
    PAGE_SYMBOL to "!@#$%^&*()_+-=[]{}|;':\",./<>?`~".toList().map { it.toString() }
)

@Composable
fun EmergencyUnlockScreen(
    onUnlockSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val keyLength = EmergencyKeyGenerator.keyLength()
    var targetKey by remember { mutableStateOf(EmergencyKeyGenerator.generate()) }
    var inputBuffer by remember { mutableStateOf(List(keyLength) { "" }) }
    var currentPosition by remember { mutableIntStateOf(0) }
    var showKey by remember { mutableStateOf(true) }
    var currentPage by remember { mutableIntStateOf(PAGE_UPPER) }
    var errorShake by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 自动验证
    LaunchedEffect(inputBuffer) {
        val filled = inputBuffer.count { it.isNotEmpty() }
        if (filled == keyLength) {
            val inputStr = inputBuffer.joinToString("")
            if (EmergencyKeyGenerator.verify(inputStr, targetKey)) {
                snackbarHostState.showSnackbar("验证通过，解锁中...")
                delay(500)
                onUnlockSuccess()
            } else {
                errorShake = true
                delay(600)
                errorShake = false
                inputBuffer = List(keyLength) { "" }
                currentPosition = 0
                targetKey = EmergencyKeyGenerator.generate()
                showKey = true
                currentPage = PAGE_UPPER
                snackbarHostState.showSnackbar("密钥错误，已重新生成")
            }
        }
    }

    // 抖动动画
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(errorShake) {
        if (errorShake) {
            for (i in 0..5) {
                shakeOffset.animateTo(
                    targetValue = if (i % 2 == 0) -8f else 8f,
                    animationSpec = tween(60, easing = FastOutSlowInEasing)
                )
            }
            shakeOffset.animateTo(0f, tween(60))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .offset(x = shakeOffset.value.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "🔓 紧急解锁",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "逐个输入64位密钥。故意设计得很痛苦。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 目标密钥卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("目标密钥", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(
                                onClick = { showKey = !showKey },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text(if (showKey) "隐藏" else "显示", fontSize = 11.sp) }
                            TextButton(
                                onClick = {
                                    targetKey = EmergencyKeyGenerator.generate()
                                    inputBuffer = List(keyLength) { "" }
                                    currentPosition = 0
                                    showKey = true
                                    currentPage = PAGE_UPPER
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("重生成", fontSize = 11.sp) }
                        }
                    }
                    if (showKey) {
                        Text(
                            targetKey,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = 1.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        Text(
                            "•".repeat(keyLength),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 进度槽位
            val filledCount = inputBuffer.count { it.isNotEmpty() }
            Text(
                "第 ${currentPosition + 1} / $keyLength 位",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))

            // 已输入进度条（紧凑显示）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                for (i in 0 until keyLength) {
                    val ch = inputBuffer[i]
                    val isCurrent = i == currentPosition
                    val isFilled = ch.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                when {
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    isFilled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                                RoundedCornerShape(2.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isFilled) {
                            Text(
                                ch,
                                fontSize = 7.sp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            Text(
                "$filledCount / $keyLength",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 键盘区域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Bottom
            ) {
                // Tab切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("ABC" to PAGE_UPPER, "abc" to PAGE_LOWER, "123" to PAGE_NUMBER, "#\$%" to PAGE_SYMBOL).forEach { (label, page) ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 2.dp)
                                .clickable { currentPage = page },
                            shape = RoundedCornerShape(8.dp),
                            color = if (currentPage == page)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else
                                Color.Transparent
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(vertical = 6.dp),
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp,
                                fontWeight = if (currentPage == page) FontWeight.Bold else FontWeight.Normal,
                                color = if (currentPage == page)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 字符网格
                val chars = PAGES[currentPage] ?: emptyList()
                val cols = if (currentPage == PAGE_NUMBER) 5 else if (currentPage == PAGE_SYMBOL) 7 else 7
                val rows = (chars.size + cols - 1) / cols

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (r in 0 until rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            for (c in 0 until cols) {
                                val idx = r * cols + c
                                if (idx < chars.size) {
                                    val ch = chars[idx]
                                    KeyButton(
                                        text = ch,
                                        onClick = {
                                            val buf = inputBuffer.toMutableList()
                                            buf[currentPosition] = ch
                                            inputBuffer = buf
                                            if (currentPosition < keyLength - 1) {
                                                // 智能切换页码：大写字母后切小写等
                                                // 不自动切页，让用户自己切（增加痛苦）
                                                currentPosition++
                                            }
                                        }
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 底部行：退格
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    KeyButton(
                        text = "⌫",
                        onClick = {
                            if (currentPosition > 0 || inputBuffer.getOrNull(currentPosition)?.isNotEmpty() == true) {
                                val buf = inputBuffer.toMutableList()
                                buf[currentPosition] = ""
                                if (currentPosition > 0) {
                                    buf[currentPosition - 1] = ""
                                    currentPosition--
                                }
                                inputBuffer = buf
                            }
                        },
                        weight = 2f,
                        isSpecial = true
                    )
                    KeyButton(
                        text = "清空",
                        onClick = {
                            scope.launch {
                                inputBuffer = List(keyLength) { "" }
                                currentPosition = 0
                            }
                        },
                        weight = 1f,
                        isDanger = true
                    )
                    KeyButton(
                        text = "返回",
                        onClick = onBack,
                        weight = 1f
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RowScope.KeyButton(
    text: String,
    onClick: () -> Unit,
    weight: Float = 1f,
    isSpecial: Boolean = false,
    isDanger: Boolean = false
) {
    val scale = remember { Animatable(1f) }
    Surface(
        modifier = Modifier
            .weight(weight)
            .height(38.dp)
            .scale(scale.value),
        shape = RoundedCornerShape(6.dp),
        color = when {
            isDanger -> MaterialTheme.colorScheme.errorContainer
            isSpecial -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isSpecial || isDanger) 0.dp else 1.dp,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                fontSize = if (text.length > 1) 12.sp else 16.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = when {
                    isDanger -> MaterialTheme.colorScheme.onErrorContainer
                    isSpecial -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}
