package com.applan.ui.emergency

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.applan.R
import com.applan.util.EmergencyKeyGenerator
import kotlinx.coroutines.delay

/**
 * 紧急解锁界面
 *
 * 设计要点：
 * 1. 16位密钥，5个可见槽位轮播（类似轮播图：当前位在中间放大，两侧渐小渐隐）
 * 2. 目标密钥逐位显示：输入到第N位，只显示前N位目标字符（减少记忆负担？不，故意全部显示但分组）
 *    实际上：目标密钥分组显示（4个一组，共4组），随输入进度渐入
 * 3. 自动弹键盘：进入即聚焦，点击任何位置都能重新聚焦
 * 4. 简化UI：去掉"点击此处调出键盘"，整个输入区域可点击
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyUnlockScreen(
    onUnlockSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val keyLength = EmergencyKeyGenerator.keyLength() // 16
    val groupSize = 4 // 每4个字符一组
    val visibleSlots = 5 // 同时可见的槽位数（必须是奇数，中间位最大）

    var targetKey by remember { mutableStateOf(EmergencyKeyGenerator.generate()) }
    var inputText by remember { mutableStateOf("") }
    var errorShake by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // 自动聚焦弹出键盘 - 进入页面即弹
    LaunchedEffect(Unit) {
        delay(150)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // 失焦时自动重新聚焦（防止键盘意外收起）
    LaunchedEffect(isFocused) {
        if (!isFocused) {
            delay(300)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // 自动验证
    LaunchedEffect(inputText) {
        if (inputText.length == keyLength) {
            if (EmergencyKeyGenerator.verify(inputText, targetKey)) {
                keyboardController?.hide()
                delay(300)
                onUnlockSuccess()
            } else {
                errorShake = true
                delay(600)
                errorShake = false
                inputText = ""
                targetKey = EmergencyKeyGenerator.generate()
            }
        }
    }

    // 抖动动画
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(errorShake) {
        if (errorShake) {
            for (i in 0..5) {
                shakeOffset.animateTo(
                    targetValue = if (i % 2 == 0) -12f else 12f,
                    animationSpec = tween(55, easing = FastOutSlowInEasing)
                )
            }
            shakeOffset.animateTo(0f, tween(55))
        }
    }

    // 当前输入位置（0-based）
    val currentPos = inputText.length.coerceAtMost(keyLength - 1)

    // 计算轮播窗口：可见槽位的中心是currentPos，左右各(visibleSlots-1)/2个
    val halfVisible = visibleSlots / 2
    val windowStart = (currentPos - halfVisible).coerceAtLeast(0)
    val windowEnd = (windowStart + visibleSlots - 1).coerceAtMost(keyLength - 1)
    // 调整windowStart确保窗口大小始终为visibleSlots（在边界时）
    val adjustedWindowStart = (windowEnd - visibleSlots + 1).coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("紧急解锁", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = {
                        keyboardController?.hide()
                        onBack()
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回",
                            modifier = Modifier.size(22.dp)
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
                .offset(x = shakeOffset.value.dp)
                // 整个区域可点击聚焦
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // 提示文字
            Text(
                "输入紧急密钥解锁。故意设计得很痛苦。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ===== 目标密钥卡片（分组显示，4个一组）=====
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("目标密钥", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(
                            onClick = {
                                targetKey = EmergencyKeyGenerator.generate()
                                inputText = ""
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("重生成", fontSize = 12.sp) }
                    }

                    // 分组显示目标密钥：4个一组，组间有间隔
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (group in 0 until keyLength / groupSize) {
                            if (group > 0) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    " ",
                                    fontSize = 18.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            for (i in 0 until groupSize) {
                                val charIndex = group * groupSize + i
                                val char = targetKey[charIndex]
                                // 已输入到该位置或之后才显示，未到达的位置显示•
                                val isRevealed = charIndex <= inputText.length
                                val isCurrent = charIndex == inputText.length
                                val isError = inputText.length > charIndex && inputText[charIndex] != char

                                Text(
                                    text = if (isRevealed) char.toString() else "•",
                                    fontSize = 18.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                                    color = when {
                                        isError -> MaterialTheme.colorScheme.error
                                        isCurrent -> MaterialTheme.colorScheme.primary
                                        isRevealed -> MaterialTheme.colorScheme.onSurface
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    },
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // ===== 5槽位轮播输入区域（核心UI）=====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 只渲染可见窗口内的槽位
                    for (i in adjustedWindowStart..windowEnd) {
                        val distance = kotlin.math.abs(i - currentPos)
                        val isCurrent = i == currentPos
                        val isFilled = i < inputText.length
                        val isError = isFilled && inputText[i] != targetKey[i]
                        val char = if (isFilled) inputText[i].toString() else ""

                        // 根据距离计算缩放/透明度/大小（中间最大，两侧渐小渐隐）
                        val scale = when {
                            distance == 0 -> 1.25f
                            distance == 1 -> 0.9f
                            distance == 2 -> 0.7f
                            else -> 0.55f
                        }
                        val alpha = when {
                            distance == 0 -> 1f
                            distance == 1 -> 0.7f
                            distance == 2 -> 0.4f
                            else -> 0.2f
                        }
                        val slotSize = when {
                            distance == 0 -> 54.dp
                            distance == 1 -> 42.dp
                            distance == 2 -> 34.dp
                            else -> 28.dp
                        }

                        // 槽位间间距（中心处大，边缘小）
                        if (i > adjustedWindowStart) {
                            Spacer(modifier = Modifier.width(if (distance <= 1) 4.dp else 2.dp))
                        }

                        // 动画：槽位进入/离开时有平滑过渡
                        val animatedScale by animateFloatAsState(
                            targetValue = scale,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "slotScale"
                        )
                        val animatedAlpha by animateFloatAsState(
                            targetValue = alpha,
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            label = "slotAlpha"
                        )

                        Box(
                            modifier = Modifier
                                .size(slotSize)
                                .scale(animatedScale)
                                .alpha(animatedAlpha)
                                .background(
                                    when {
                                        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                        isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        isFilled -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    },
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    width = if (isCurrent) 2.5.dp else 1.dp,
                                    color = when {
                                        isError -> MaterialTheme.colorScheme.error
                                        isCurrent -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = char,
                                fontSize = if (isCurrent) 26.sp else 20.sp,
                                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    isError -> MaterialTheme.colorScheme.error
                                    isFilled -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                }
                            )
                        }
                    }
                }

                // 隐藏的TextField用于接收键盘输入
                BasicTextField(
                    value = inputText,
                    onValueChange = { newText ->
                        val filtered = newText.take(keyLength).filter {
                            it.isLetterOrDigit() || it in "!@#$%^&*()_+-=[]{}|;':\",./<>?`~"
                        }
                        inputText = filtered
                    },
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused },
                    textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(Color.Transparent)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 进度指示
            Text(
                "${inputText.length} / $keyLength",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.weight(1f))

            // 底部操作区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inputText.isNotEmpty()) {
                    TextButton(
                        onClick = { inputText = "" },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("清空输入", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
