package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.ui.feed.FeedViewModel
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteTimeline(
    state: FeedViewModel.UiState,
    ownPubkey: String?,
    onUserClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    onLike: (eventId: String, authorPubkey: String) -> Unit,
    onUnlike: (eventId: String) -> Unit,
    onEmojiReact: (eventId: String, authorPubkey: String, option: ReactionOption) -> Unit,
    onEmojiUnreact: (eventId: String, option: ReactionOption) -> Unit,
    onDelete: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    onRepost: (NostrEvent) -> Unit,
    onUnrepost: (eventId: String) -> Unit,
    onReport: (event: NostrEvent, reason: String, detail: String) -> Unit,
    onHashtagClick: ((tag: String) -> Unit)? = null,
    emptyText: String = "ポストがありません",
    scrollToTopRequest: Int = 0,
    listState: LazyListState? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    header: LazyListScope.() -> Unit = {},
) {
    val mutedPubkeys by MuteStore.mutedPubkeys.collectAsState()
    val timelineListState = listState ?: rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var handledScrollToTopRequest by rememberSaveable {
        mutableStateOf(scrollToTopRequest)
    }

    LaunchedEffect(timelineListState, state.canLoadMore, state.isLoadingMore) {
        if (!state.canLoadMore || state.isLoadingMore) return@LaunchedEffect

        snapshotFlow {
            val layoutInfo = timelineListState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= layoutInfo.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadMore() }
    }

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > handledScrollToTopRequest) {
            handledScrollToTopRequest = scrollToTopRequest
            timelineListState.animateScrollToItem(0)
        }
    }

    @Composable
    fun TimelineList(listModifier: Modifier) {
        LazyColumn(
            state = timelineListState,
            modifier = listModifier,
        ) {
            header()

            noteListItems(
                state = state,
                ownPubkey = ownPubkey,
                onUserClick = onUserClick,
                onLike = onLike,
                onUnlike = onUnlike,
                onEmojiReact = onEmojiReact,
                onEmojiUnreact = onEmojiUnreact,
                onDelete = onDelete,
                onReply = onReply,
                onOpenReplies = onOpenReplies,
                onOpenLikes = onOpenLikes,
                onOpenReposts = onOpenReposts,
                onRepost = { eventId, _ ->
                    state.events.find { it.id == eventId }?.let(onRepost)
                },
                onUnrepost = onUnrepost,
                onReport = { eventId, reason, detail ->
                    state.events.find { it.id == eventId }?.let { onReport(it, reason, detail) }
                },
                onHashtagClick = onHashtagClick,
                onMuteUser = MuteStore::mute,
                onUnmuteUser = MuteStore::unmute,
                mutedPubkeys = mutedPubkeys,
                emptyText = emptyText,
            )
        }
    }

    if (onRefresh != null) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize(),
        ) {
            TimelineList(Modifier.fillMaxSize())
        }
    } else {
        TimelineList(modifier.fillMaxSize())
    }
}
