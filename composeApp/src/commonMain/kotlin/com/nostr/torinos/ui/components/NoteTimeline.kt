package com.nostr.torinos.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.ui.feed.FeedViewModel
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
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
    onReply: ((event: NostrEvent, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    onRefreshReactions: ((eventId: String) -> Unit)? = null,
    onRepost: (NostrEvent) -> Unit,
    onUnrepost: (eventId: String) -> Unit,
    onReport: (event: NostrEvent, reason: String, detail: String) -> Unit,
    onHashtagClick: ((tag: String) -> Unit)? = null,
    emptyText: String = "ポストがありません",
    scrollToTopRequest: Int = 0,
    resetToTopRequest: Int = 0,
    listState: LazyListState? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    emptyStateDelayMillis: Long = 0L,
    eventEnterFadeMillis: Int = 0,
    stageInitialEvents: Boolean = false,
    initialEventQuietMillis: Long = 200L,
    initialEventMaxWaitMillis: Long = 500L,
    header: LazyListScope.() -> Unit = {},
) {
    val muteStore = LocalAccountSession.current?.muteStore
    val mutedPubkeys = muteStore?.mutedPubkeys?.collectAsState()?.value.orEmpty()
    val timelineListState = listState ?: rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var handledScrollToTopRequest by rememberSaveable {
        mutableStateOf(scrollToTopRequest)
    }
    var isEmptyMessageVisible by remember(emptyStateDelayMillis) {
        mutableStateOf(emptyStateDelayMillis <= 0L)
    }
    var isInitialEventContentComposed by remember(resetToTopRequest, stageInitialEvents) {
        mutableStateOf(!stageInitialEvents)
    }
    var isEventContentVisible by remember(resetToTopRequest, stageInitialEvents) {
        mutableStateOf(!stageInitialEvents)
    }
    var hasInitialEventBatchStarted by remember(resetToTopRequest, stageInitialEvents) {
        mutableStateOf(false)
    }
    var initialEventRevealRequest by remember(resetToTopRequest, stageInitialEvents) {
        mutableIntStateOf(0)
    }

    LaunchedEffect(state.isInitialLoad, state.events.isEmpty(), emptyStateDelayMillis) {
        if (emptyStateDelayMillis <= 0L) {
            isEmptyMessageVisible = true
            return@LaunchedEffect
        }
        if (state.isInitialLoad || state.events.isNotEmpty()) {
            isEmptyMessageVisible = false
            return@LaunchedEffect
        }
        isEmptyMessageVisible = false
        delay(emptyStateDelayMillis)
        isEmptyMessageVisible = true
    }

    LaunchedEffect(
        state.initialFeedState,
        state.events.isEmpty(),
        resetToTopRequest,
        stageInitialEvents,
    ) {
        if (
            !stageInitialEvents ||
            isInitialEventContentComposed ||
            state.initialFeedState == FeedViewModel.InitialFeedState.Loading ||
            state.events.isNotEmpty()
        ) {
            return@LaunchedEffect
        }
        if (state.initialFeedState == FeedViewModel.InitialFeedState.Empty) {
            delay(emptyStateDelayMillis)
        }
        isInitialEventContentComposed = true
        isEventContentVisible = true
    }

    LaunchedEffect(
        state.events.size,
        state.events.firstOrNull()?.id,
        resetToTopRequest,
        stageInitialEvents,
    ) {
        if (!stageInitialEvents || isInitialEventContentComposed || state.events.isEmpty()) {
            return@LaunchedEffect
        }
        hasInitialEventBatchStarted = true
        delay(initialEventQuietMillis)
        initialEventRevealRequest++
    }

    LaunchedEffect(
        hasInitialEventBatchStarted,
        resetToTopRequest,
        stageInitialEvents,
    ) {
        if (!stageInitialEvents || !hasInitialEventBatchStarted || isInitialEventContentComposed) {
            return@LaunchedEffect
        }
        delay(initialEventMaxWaitMillis)
        initialEventRevealRequest++
    }

    LaunchedEffect(initialEventRevealRequest, resetToTopRequest, stageInitialEvents) {
        if (!stageInitialEvents || initialEventRevealRequest <= 0 || isInitialEventContentComposed) {
            return@LaunchedEffect
        }
        isInitialEventContentComposed = true
        withFrameNanos { }
        timelineListState.scrollToItem(0)
        isEventContentVisible = true
    }

    LaunchedEffect(
        timelineListState,
        state.canLoadMore,
        state.isLoadingMore,
        state.historyRequestGeneration,
        state.events.size,
    ) {
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

    LaunchedEffect(resetToTopRequest) {
        if (resetToTopRequest > 0) {
            timelineListState.scrollToItem(0)
        }
    }

    @Composable
    fun TimelineList(listModifier: Modifier) {
        val presentedState = when {
            !isInitialEventContentComposed -> state.copy(
                events = emptyList(),
                isInitialLoad = true,
                initialFeedState = FeedViewModel.InitialFeedState.Loading,
            )
            stageInitialEvents &&
                state.events.isEmpty() &&
                state.initialFeedState == FeedViewModel.InitialFeedState.Empty &&
                !isEmptyMessageVisible -> state.copy(
                    isInitialLoad = true,
                    initialFeedState = FeedViewModel.InitialFeedState.Slow,
                )
            else -> state
        }
        LazyColumn(
            state = timelineListState,
            modifier = listModifier,
        ) {
            header()

            noteListItems(
                state = presentedState,
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
                onRefreshReactions = onRefreshReactions,
                onRepost = { eventId, _ ->
                    state.events.find { it.id == eventId }?.let(onRepost)
                },
                onUnrepost = onUnrepost,
                onReport = { eventId, reason, detail ->
                    state.events.find { it.id == eventId }?.let { onReport(it, reason, detail) }
                },
                onHashtagClick = onHashtagClick,
                onMuteUser = { muteStore?.mute(it) },
                onUnmuteUser = { muteStore?.unmute(it) },
                mutedPubkeys = mutedPubkeys,
                emptyText = emptyText,
                emptyContent = {
                    AnimatedVisibility(
                        visible = isEmptyMessageVisible,
                        enter = fadeIn(),
                    ) {
                        EmptyTimelineMessage(emptyText)
                    }
                },
                eventContentVisible = isEventContentVisible,
                eventEnterFadeMillis = eventEnterFadeMillis,
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
