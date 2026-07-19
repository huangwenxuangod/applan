package com.applan.ui.common

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 边缘左滑返回手势修饰符
 *
 * 检测用户从屏幕右边缘向左滑动（类似iOS返回手势），触发onBack回调。
 * 只响应从右边缘（edgeWidth范围内）开始的左滑，避免和页面内的横向滚动冲突。
 *
 * @param enabled 是否启用
 * @param edgeWidth 响应边缘宽度（默认30dp）
 * @param minSwipeDistance 触发返回的最小滑动距离（默认80dp）
 * @param onBack 触发返回时的回调
 */
fun Modifier.swipeToBack(
    enabled: Boolean = true,
    edgeWidth: Dp = 30.dp,
    minSwipeDistance: Dp = 80.dp,
    onBack: () -> Unit
): Modifier = composed {
    if (!enabled) return@composed this

    val density = LocalDensity.current
    val edgeWidthPx = with(density) { edgeWidth.toPx() }
    val minDistancePx = with(density) { minSwipeDistance.toPx() }

    var startFromEdge by remember { mutableStateOf<Boolean?>(null) }
    var totalDragX by remember { mutableFloatStateOf(0f) }

    this.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                val screenWidth = size.width.toFloat()
                startFromEdge = offset.x >= screenWidth - edgeWidthPx
                totalDragX = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                if (startFromEdge != true) return@detectHorizontalDragGestures
                totalDragX += dragAmount
                change.consume()
            },
            onDragEnd = {
                if (startFromEdge == true && totalDragX < -minDistancePx) {
                    onBack()
                }
                startFromEdge = null
                totalDragX = 0f
            },
            onDragCancel = {
                startFromEdge = null
                totalDragX = 0f
            }
        )
    }
}
