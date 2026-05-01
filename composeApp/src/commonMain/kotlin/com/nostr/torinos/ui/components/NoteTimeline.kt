package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.ui.feed.FeedViewModel
import androidx.compose.runtime.collectAsState

@Composable
fun NoteTimeline(
    state: FeedViewModel.UiState,
    ownPubkey: String?,
    onUserClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    onLike: (eventId: String, authorPubkey: String) -> Unit,
    onUnlike: (eventId: String) -> Unit,
    onDelete: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    onRepost: (NostrEvent) -> Unit,
    onUnrepost: (eventId: String) -> Unit,
    emptyText: String = "ポストがありません",
    scrollToTopRequest: Int = 0,
    listState: LazyListState? = null,
    header: LazyListScope.() -> Unit = {},
) {
    val mutedPubkeys by MuteStore.mutedPubkeys.collectAsState()
    val timelineListState = listState ?: rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val reachedBottom by remember {
        derivedStateOf {
            val lastVisible = timelineListState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible != null && lastVisible.index >= timelineListState.layoutInfo.totalItemsCount - 3
        }
    }

    LaunchedEffect(reachedBottom, state.canLoadMore, state.isLoadingMore) {
        if (reachedBottom && state.canLoadMore && !state.isLoadingMore) onLoadMore()
    }

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            timelineListState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = timelineListState,
        modifier = modifier.fillMaxSize(),
    ) {
        header()

        noteListItems(
            state = state,
            ownPubkey = ownPubkey,
            onUserClick = onUserClick,
            onLike = onLike,
            onUnlike = onUnlike,
            onDelete = onDelete,
            onReply = onReply,
            onOpenReplies = onOpenReplies,
            onOpenLikes = onOpenLikes,
            onOpenReposts = onOpenReposts,
            onRepost = { eventId, _ ->
                state.events.find { it.id == eventId }?.let(onRepost)
            },
            onUnrepost = onUnrepost,
            mutedPubkeys = mutedPubkeys,
            emptyText = emptyText,
        )
    }
}
