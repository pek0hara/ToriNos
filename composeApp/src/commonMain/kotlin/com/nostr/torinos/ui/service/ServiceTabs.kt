package com.nostr.torinos.ui.service

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

enum class ServiceTab(val label: String) {
    Channels("チャンネル"),
    Status("ステータス"),
}

@Composable
fun ServiceTabRow(
    selectedTab: ServiceTab,
    onTabSelected: (ServiceTab) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = ServiceTab.entries.indexOf(selectedTab).coerceAtLeast(0)) {
        ServiceTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = { Text(tab.label) },
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
            when {
                dragAmount < -SwipeThresholdPx && selectedTab == ServiceTab.Channels ->
                    onTabSelected(ServiceTab.Status)
                dragAmount > SwipeThresholdPx && selectedTab == ServiceTab.Status ->
                    onTabSelected(ServiceTab.Channels)
            }
        },
        onDragCancel = { dragAmount = 0f },
    )
}

private const val SwipeThresholdPx = 80f
