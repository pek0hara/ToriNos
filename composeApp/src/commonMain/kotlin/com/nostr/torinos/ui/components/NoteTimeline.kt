package com.nostr.torinos.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.network.DisplayPreferencesStore
import com.nostr.torinos.ui.feed.FeedViewModel
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

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
    onAtTopChanged: (Boolean) -> Unit = {},
    topOverlayVisibility: Float = 1f,
) {
    val muteStore = LocalAccountSession.current?.muteStore
    val mutedPubkeys = muteStore?.mutedPubkeys?.collectAsState()?.value.orEmpty()
    val timelineListState = listState ?: rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val coroutineScope = rememberCoroutineScope()

    // listStateは呼び出し元(FeedScreen)でviewModelの切り替えをまたいで使い回されるため、
    // このeffect自体はtimelineListStateにしか依存させず、常に最新のonAtTopChangedを
    // rememberUpdatedStateで読む。ここをキーに含めて再起動する形にすると、
    // 新しいラムダは再コンポーズのたびに新しいインスタンスとして生成されるため、
    // viewModelが変わっていなくても毎回購読し直してしまう。
    val currentOnAtTopChanged = rememberUpdatedState(onAtTopChanged)
    LaunchedEffect(timelineListState) {
        snapshotFlow {
            timelineListState.firstVisibleItemIndex == 0 && timelineListState.firstVisibleItemScrollOffset == 0
        }
            .distinctUntilChanged()
            .collect { atTop -> currentOnAtTopChanged.value(atTop) }
    }
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

    val prefetchPlatformContext = LocalPlatformContext.current
    val prefetchedImageUrls = remember { mutableSetOf<String>() }
    LaunchedEffect(timelineListState, state.events) {
        snapshotFlow {
            timelineListState.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String
        }
            .distinctUntilChanged()
            .collect { lastVisibleEventId ->
                if (!DisplayPreferencesStore.showImagePreviews.value) return@collect
                val lastIndex = lastVisibleEventId
                    ?.let { id -> state.events.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 }
                    ?: return@collect
                val imageLoader = SingletonImageLoader.get(prefetchPlatformContext)
                state.events.drop(lastIndex + 1).take(ImagePrefetchAheadCount).forEach { event ->
                    val images = state.parsedContents[event.id]?.images ?: return@forEach
                    val request = firstImagePreviewRequest(prefetchPlatformContext, images) ?: return@forEach
                    if (prefetchedImageUrls.add(request.data.toString())) {
                        imageLoader.enqueue(request)
                        // セッションが長く続いてもSet自体が際限なく肥大化しないよう上限で古い記録から破棄する。
                        while (prefetchedImageUrls.size > PrefetchedImageUrlCacheLimit) {
                            prefetchedImageUrls.remove(prefetchedImageUrls.first())
                        }
                    }
                }
            }
    }

    var isScrollSettled by remember { mutableStateOf(!timelineListState.isScrollInProgress) }
    LaunchedEffect(timelineListState) {
        snapshotFlow { timelineListState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) {
                    isScrollSettled = false
                } else {
                    delay(ScrollSettleDebounceMillis)
                    isScrollSettled = true
                }
            }
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
                deferWebViewLoad = !isScrollSettled,
            )
        }
    }

    Box(modifier = modifier) {
        if (onRefresh != null) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                TimelineList(Modifier.fillMaxSize())
            }
        } else {
            TimelineList(Modifier.fillMaxSize())
        }

        if (state.newPostCount > 0 && topOverlayVisibility > 0f) {
            NewPostsButton(
                count = state.newPostCount,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .alpha(topOverlayVisibility.coerceIn(0f, 1f))
                    .padding(top = 8.dp),
                onClick = {
                    coroutineScope.launch { timelineListState.scrollToItem(0) }
                },
            )
        }
    }
}

@Composable
private fun NewPostsButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(32.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "新しい投稿 ${count.coerceAtMost(99)}${if (count > 99) "+" else ""}件",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** 直近の可視投稿より後ろ、何件先までサムネイル1枚目をプリフェッチするか。 */
private const val ImagePrefetchAheadCount = 3

/** プリフェッチ済みURLの重複防止セットの上限。長時間セッションでの際限ない肥大化を防ぐ。 */
private const val PrefetchedImageUrlCacheLimit = 300

/** スクロール停止からこの時間が経つまでは「停止」とみなさない（短い指の離し直しでの反復開始を防ぐ）。 */
private const val ScrollSettleDebounceMillis = 200L
