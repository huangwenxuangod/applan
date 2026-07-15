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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyUnlockScreen(
    onUnlockSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val keyLength = EmergencyKeyGenerator.keyLength()
    var targetKey by remember { mutableStateOf(EmergencyKeyGenerator.generate()) }
    var inputText by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(true) }
    var errorShake by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // 自动聚焦弹出键盘
    LaunchedEffect(Unit) {
        delay(200)
        focusRequester.requestFocus()
        keyboardController?.show()
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
                delay(500)
                errorShake = false
                inputText = ""
                targetKey = EmergencyKeyGenerator.generate()
                showKey = true
            }
        }
    }

    // 抖动动画
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(errorShake) {
        if (errorShake) {
            for (i in 0..5) {
                shakeOffset.animateTo(
                    targetValue = if (i % 2 == 0) -10f else 10f,
                    animationSpec = tween(50, easing = FastOutSlowInEasing)
                )
            }
            shakeOffset.animateTo(0f, tween(50))
        }
    }

    // 当前输入位置（0-based）
    val currentPos = inputText.length.coerceAtMost(keyLength - 1)

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
                .offset(x = shakeOffset.value.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // 提示文字
            Text(
                "输入紧急密钥解锁。故意设计得很痛苦。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 目标密钥卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("目标密钥", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(
                                onClick = { showKey = !showKey },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text(if (showKey) "隐藏" else "显示", fontSize = 12.sp) }
                            TextButton(
                                onClick = {
                                    targetKey = EmergencyKeyGenerator.generate()
                                    inputText = ""
                                    showKey = true
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("重生成", fontSize = 12.sp) }
                        }
                    }
                    if (showKey) {
                        Text(
                            targetKey,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = 4.sp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    } else {
                        Text(
                            "•".repeat(keyLength),
                            fontSize = 16.sp,
                            letterSpacing = 4.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // ===== 轮播字符槽位（核心UI）=====
            // 设计：8个格子横向排列，当前位置的格子在中间最大，两侧渐小渐隐
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until keyLength) {
                        val distance = kotlin.math.abs(i - currentPos)
                        val isCurrent = i == currentPos
                        val isFilled = i < inputText.length
                        val char = if (isFilled) inputText[i].toString() else ""

                        // 根据距离计算缩放和透明度
                        val scale = when {
                            distance == 0 -> 1.3f
                            distance == 1 -> 0.9f
                            distance == 2 -> 0.75f
                            else -> 0.6f
                        }
                        val alpha = when {
                            distance == 0 -> 1f
                            distance == 1 -> 0.7f
                            distance == 2 -> 0.45f
                            else -> 0.25f
                        }

                        Spacer(modifier = Modifier.width(if (distance == 0) 6.dp else 3.dp))

                        Box(
                            modifier = Modifier
                                .size(if (isCurrent) 52.dp else (40f - distance * 5f).dp.coerceAtLeast(28.dp))
                                .scale(scale * if (isCurrent) 1f else 1f)
                                .alpha(alpha)
                                .background(
                                    when {
                                        isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        isFilled -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    },
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    width = if (isCurrent) 2.dp else 1.dp,
                                    color = if (isCurrent)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = char,
                                fontSize = if (isCurrent) 24.sp else 18.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                color = if (isFilled)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        }

                        Spacer(modifier = Modifier.width(if (distance == 0) 6.dp else 3.dp))
                    }
                }

                // 隐藏的TextField用于接收键盘输入
                BasicTextField(
                    value = inputText,
                    onValueChange = { newText ->
                        // 只保留keyLength长度，过滤控制字符
                        val filtered = newText.take(keyLength).filter { it.isLetterOrDigit() || it in "!@#$%^&*()_+-=[]{}|;':\",./<>?`~" }
                        inputText = filtered
                    },
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(Color.Transparent)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 进度
            Text(
                "${inputText.length} / $keyLength",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 点击任意位置重新聚焦
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 20.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 清空按钮
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
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "点击此处调出键盘",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}
