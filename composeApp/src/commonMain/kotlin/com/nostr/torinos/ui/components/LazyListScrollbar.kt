package com.nostr.torinos.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun LazyListScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    val thumbAlpha by animateFloatAsState(
        targetValue = if (isDragging || state.isScrollInProgress) 0.6f else 0.3f,
        animationSpec = tween(300),
        label = "scrollbar_alpha",
    )

    BoxWithConstraints(modifier = modifier) {
        val viewportHeightPx = constraints.maxHeight.toFloat()

        val layoutInfo = state.layoutInfo
        val totalItems = layoutInfo.totalItemsCount
        val visibleItems = layoutInfo.visibleItemsInfo

        if (totalItems <= 0 || visibleItems.isEmpty() || viewportHeightPx <= 0f) return@BoxWithConstraints

        val avgItemSize = visibleItems.sumOf { it.size } / visibleItems.size.toFloat()
        val totalContentHeight = totalItems * avgItemSize

        val thumbHeightPx = (viewportHeightPx * viewportHeightPx / totalContentHeight)
            .coerceIn(40f, viewportHeightPx)

        val firstItem = visibleItems.first()
        val scrolledPx = firstItem.index * avgItemSize - firstItem.offset
        val maxScroll = totalContentHeight - viewportHeightPx
        val thumbTopPx = if (maxScroll > 0f) {
            (scrolledPx / maxScroll) * (viewportHeightPx - thumbHeightPx)
        } else 0f

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    detectVerticalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // ドラッグ中は state から現在位置をリアルタイムで読む
                            val info = state.layoutInfo
                            val items = info.visibleItemsInfo
                            if (items.isEmpty()) return@detectVerticalDragGestures
                            val avg = items.sumOf { it.size } / items.size.toFloat()
                            val total = info.totalItemsCount
                            val totalHeight = total * avg
                            val thumbH = (viewportHeightPx * viewportHeightPx / totalHeight)
                                .coerceIn(40f, viewportHeightPx)
                            val trackRange = viewportHeightPx - thumbH
                            if (trackRange <= 0f) return@detectVerticalDragGestures
                            val first = items.first()
                            val currentScrolled = first.index * avg - first.offset
                            val currentMax = totalHeight - viewportHeightPx
                            val currentFraction = if (currentMax > 0f) currentScrolled / currentMax else 0f
                            val newFraction = (currentFraction + dragAmount / trackRange).coerceIn(0f, 1f)
                            val targetItem = (newFraction * (total - 1)).roundToInt()
                            coroutineScope.launch { state.scrollToItem(targetItem) }
                        },
                    )
                },
        ) {
            val trackColor = Color.Gray.copy(alpha = 0.15f)
            val thumbColor = Color.Gray.copy(alpha = thumbAlpha)
            val barWidth = 4.dp.toPx()
            val cornerRadius = CornerRadius(2.dp.toPx())
            val x = size.width / 2 - barWidth / 2

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(x, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = cornerRadius,
            )
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(x, thumbTopPx),
                size = Size(barWidth, thumbHeightPx),
                cornerRadius = cornerRadius,
            )
        }
    }
}
