package com.nostr.torinos.ui.profile

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

internal enum class ProfileTimelineTab(val label: String) {
    Posts("投稿"),
    PostsAndReplies("投稿と返信"),
}

internal fun Modifier.profileTimelineTabSwipe(
    currentTab: ProfileTimelineTab,
    onTabChange: (ProfileTimelineTab) -> Unit,
): Modifier = pointerInput(currentTab) {
    var dragAmount = 0f
    detectHorizontalDragGestures(
        onDragStart = { dragAmount = 0f },
        onHorizontalDrag = { change, amount ->
            dragAmount += amount
            change.consume()
        },
        onDragEnd = {
            when {
                dragAmount < -SwipeThresholdPx && currentTab == ProfileTimelineTab.Posts ->
                    onTabChange(ProfileTimelineTab.PostsAndReplies)
                dragAmount > SwipeThresholdPx && currentTab == ProfileTimelineTab.PostsAndReplies ->
                    onTabChange(ProfileTimelineTab.Posts)
            }
        },
        onDragCancel = { dragAmount = 0f },
    )
}

private const val SwipeThresholdPx = 80f
