package com.nostr.torinos.ui.service

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ServiceTab(val label: String) {
    Articles("記事"),
    Channels("チャンネル"),
    Live("ライブ"),
    Status("ステータス"),
}

@Composable
fun ServiceTabRow(
    selectedTab: ServiceTab,
    onTabSelected: (ServiceTab) -> Unit,
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    val selectedColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(backgroundColor),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            ServiceTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTabSelected(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.label,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .width(64.dp)
                                .height(3.dp)
                                .background(
                                    color = selectedColor,
                                    shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                                ),
                        )
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

fun Modifier.serviceTabSwipe(
    selectedTab: ServiceTab,
    onTabSelected: (ServiceTab) -> Unit,
): Modifier = pointerInput(selectedTab) {
    var dragAmount = 0f
    detectHorizontalDragGestures(
        onDragStart = { dragAmount = 0f },
        onHorizontalDrag = { change, amount ->
            dragAmount += amount
            change.consume()
        },
        onDragEnd = {
            val currentIndex = ServiceTab.entries.indexOf(selectedTab)
            when {
                dragAmount < -SwipeThresholdPx && currentIndex < ServiceTab.entries.lastIndex ->
                    onTabSelected(ServiceTab.entries[currentIndex + 1])
                dragAmount > SwipeThresholdPx && currentIndex > 0 ->
                    onTabSelected(ServiceTab.entries[currentIndex - 1])
            }
        },
        onDragCancel = { dragAmount = 0f },
    )
}

private const val SwipeThresholdPx = 80f
