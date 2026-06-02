package com.nostr.torinos.ui.service

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp

enum class ServiceTab(val label: String) {
    Articles("アーティクル"),
    Channels("チャンネル"),
    Live("ライブ"),
    Status("ステータス"),
}

@Composable
fun ServiceTabRow(
    selectedTab: ServiceTab,
    onTabSelected: (ServiceTab) -> Unit,
) {
    PrimaryTabRow(
        selectedTabIndex = ServiceTab.entries.indexOf(selectedTab).coerceAtLeast(0),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        ServiceTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = { Text(tab.label, fontSize = 13.sp) },
            )
        }
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
