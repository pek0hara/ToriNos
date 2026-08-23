package com.nostr.torinos.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import com.nostr.torinos.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NoteTimeline
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenSettings: () -> Unit = {},
    onOpenRelaySettings: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    onOpenSearch: (query: String) -> Unit = {},
    ownPubkey: String? = null,
    accountResetKey: Int = 0,
    ownProfile: NostrProfile? = null,
    isAccountLoaded: Boolean = true,
    scrollToTopRequest: Int = 0,
    scrollToTopTargetTab: FeedTab = FeedTab.Following,
    onCurrentFeedTabChanged: (FeedTab) -> Unit = {},
    requestedFeedTab: FeedTab = FeedTab.Following,
    feedTabChangeRequest: Int = 0,
    followingListState: LazyListState? = null,
    globalListState: LazyListState? = null,
    hasNotifications: Boolean = false,
    chromeCollapseFraction: Float = 0f,
    onChromeCollapseFractionChange: (Float) -> Unit = {},
    /** null = グローバルフィード、非null = 特定ユーザーのポスト */
    authorPubkey: String? = null,
) {
    val relays by RelayStore.relays.collectAsState(
        initial = RelayStore.defaults.filter { it.enabled }.map { it.url },
    )
    val selectedFollowingRelayUrl by RelayStore.selectedFollowingRelayUrl.collectAsState()
    val selectedGlobalRelayUrl by RelayStore.selectedGlobalRelayUrl.collectAsState()
    val effectiveGlobalRelayUrl = selectedGlobalRelayUrl ?: relays.firstOrNull()
    val accountSession = LocalAccountSession.current
    val followedPubkeys = accountSession?.followRepository?.followedPubkeys?.collectAsState()?.value.orEmpty()
    val isFollowListLoaded = accountSession?.followRepository?.loaded?.collectAsState()?.value ?: true
    val mutedPubkeys = accountSession?.muteStore?.mutedPubkeys?.collectAsState()?.value.orEmpty()
    var showRelayMenu by remember { mutableStateOf(false) }
    var feedTab by rememberSaveable(accountResetKey, authorPubkey) { mutableStateOf(FeedTab.Following) }
    var followingFeedMode by rememberSaveable(accountResetKey, authorPubkey) {
        mutableStateOf(FollowingFeedMode.Following)
    }
    var handledScrollToTopRequest by remember { mutableStateOf(scrollToTopRequest) }
    val isLoggedOutMainFeed = authorPubkey == null && isAccountLoaded && ownPubkey == null
    val visibleFeedTabs = if (isLoggedOutMainFeed) listOf(FeedTab.Global) else FeedTab.entries
    val savedVisibleFeedTab = if (feedTab in visibleFeedTabs) feedTab else FeedTab.Global
    val pagerState = rememberPagerState(
        initialPage = visibleFeedTabs.indexOf(savedVisibleFeedTab).coerceAtLeast(0),
        pageCount = { visibleFeedTabs.size },
    )
    val coroutineScope = rememberCoroutineScope()
    val visibleFeedTab = visibleFeedTabs.getOrElse(pagerState.currentPage) { savedVisibleFeedTab }

    fun setFeedTab(tab: FeedTab) {
        val nextTab = if (isLoggedOutMainFeed && tab == FeedTab.Following) FeedTab.Global else tab
        val page = visibleFeedTabs.indexOf(nextTab)
        if (page >= 0) {
            coroutineScope.launch {
                pagerState.animateScrollToPage(page)
            }
        }
    }

    LaunchedEffect(accountResetKey, authorPubkey, visibleFeedTabs) {
        val targetTab = if (feedTab in visibleFeedTabs) feedTab else FeedTab.Global
        val targetPage = visibleFeedTabs.indexOf(targetTab).coerceAtLeast(0)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
        if (feedTab != targetTab) {
            feedTab = targetTab
        }
        onCurrentFeedTabChanged(targetTab)
    }

    LaunchedEffect(pagerState, visibleFeedTabs) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val tab = visibleFeedTabs.getOrNull(page) ?: return@collect
            if (feedTab != tab) {
                feedTab = tab
                onCurrentFeedTabChanged(tab)
            }
        }
    }

    LaunchedEffect(feedTabChangeRequest) {
        if (feedTabChangeRequest > 0 && authorPubkey == null) {
            val requestedTab = if (requestedFeedTab in visibleFeedTabs) requestedFeedTab else FeedTab.Global
            val requestedPage = visibleFeedTabs.indexOf(requestedTab).coerceAtLeast(0)
            pagerState.animateScrollToPage(requestedPage)
        }
    }

    // リレーリストが変わったら、各タブの選択中 URL を有効なものに補正する。
    LaunchedEffect(relays, selectedFollowingRelayUrl, selectedGlobalRelayUrl) {
        if (selectedFollowingRelayUrl != null && selectedFollowingRelayUrl !in relays) {
            RelayStore.setSelectedFollowingRelayUrl(null)
        }
        val fallbackGlobalRelayUrl = relays.firstOrNull()
        if (fallbackGlobalRelayUrl != null && (selectedGlobalRelayUrl == null || selectedGlobalRelayUrl !in relays)) {
            RelayStore.setSelectedGlobalRelayUrl(fallbackGlobalRelayUrl)
        }
    }

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest <= handledScrollToTopRequest) return@LaunchedEffect
        handledScrollToTopRequest = scrollToTopRequest
        when {
            authorPubkey != null -> Unit
            scrollToTopTargetTab == FeedTab.Following -> followingListState?.animateScrollToItem(0)
            scrollToTopTargetTab == FeedTab.Global -> globalListState?.animateScrollToItem(0)
        }
    }

    val selectedFeedRelayUrl = when {
        authorPubkey != null -> effectiveGlobalRelayUrl
        visibleFeedTab == FeedTab.Following -> null
        else -> effectiveGlobalRelayUrl
    }
    val canSelectAllRelays = authorPubkey == null && visibleFeedTab == FeedTab.Following
    val activeRelayUrl = selectedFeedRelayUrl
    val topBarTitle = when {
        authorPubkey != null -> selectedFeedRelayUrl?.relayDisplayName() ?: "—"
        visibleFeedTab == FeedTab.Following && followingFeedMode == FollowingFeedMode.Muted -> "ミュートフィード"
        selectedFeedRelayUrl == null && canSelectAllRelays -> "すべてのリレー"
        else -> selectedFeedRelayUrl?.relayDisplayName() ?: "—"
    }
    val feedBackgroundColor = MaterialTheme.colorScheme.background
    val feedContentColor = MaterialTheme.colorScheme.onBackground
    val activeListState = when {
        authorPubkey != null -> null
        visibleFeedTab == FeedTab.Following -> followingListState
        else -> globalListState
    }
    val density = LocalDensity.current
    val chromeCollapseDistancePx = with(density) { 112.dp.roundToPx() }
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val collapsedTopBarHeightPx = (topBarHeightPx * (1f - chromeCollapseFraction)).toInt()
    val chromeAlpha = 1f - chromeCollapseFraction
    val chromeSettleAnimation = remember { Animatable(chromeCollapseFraction) }
    var lastChromeScrollDelta by remember { mutableStateOf(0f) }
    var chromeSettleRequest by remember { mutableIntStateOf(0) }
    val chromeNestedScrollConnection = remember(
        authorPubkey,
        activeListState,
        chromeCollapseFraction,
        chromeCollapseDistancePx,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (authorPubkey != null || activeListState == null || source != NestedScrollSource.UserInput) {
                    return Offset.Zero
                }
                val delta = -available.y
                if (delta == 0f) return Offset.Zero

                val nextFraction = (chromeCollapseFraction + delta / chromeCollapseDistancePx.toFloat())
                    .coerceIn(0f, 1f)
                if (nextFraction != chromeCollapseFraction) {
                    onChromeCollapseFractionChange(nextFraction)
                }
                val consumedDelta = (nextFraction - chromeCollapseFraction) * chromeCollapseDistancePx
                if (consumedDelta != 0f) {
                    lastChromeScrollDelta = consumedDelta
                    chromeSettleRequest++
                }
                return Offset(x = 0f, y = -consumedDelta)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (chromeCollapseFraction <= 0f || chromeCollapseFraction >= 1f) {
                    return Velocity.Zero
                }
                val targetFraction = if (lastChromeScrollDelta >= 0f) 1f else 0f
                chromeSettleAnimation.snapTo(chromeCollapseFraction)
                chromeSettleAnimation.animateTo(
                    targetValue = targetFraction,
                    animationSpec = tween(ChromeSettleAnimationMillis),
                ) {
                    onChromeCollapseFractionChange(value)
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(chromeSettleRequest) {
        if (chromeSettleRequest <= 0 || chromeCollapseFraction <= 0f || chromeCollapseFraction >= 1f) {
            return@LaunchedEffect
        }
        delay(ChromeSettleDelayMillis)
        val targetFraction = if (lastChromeScrollDelta >= 0f) 1f else 0f
        chromeSettleAnimation.snapTo(chromeCollapseFraction)
        chromeSettleAnimation.animateTo(
            targetValue = targetFraction,
            animationSpec = tween(ChromeSettleAnimationMillis),
        ) {
            onChromeCollapseFractionChange(value)
        }
    }

    LaunchedEffect(activeListState, authorPubkey) {
        if (authorPubkey != null || activeListState == null) {
            onChromeCollapseFractionChange(0f)
            return@LaunchedEffect
        }

        snapshotFlow {
            activeListState.firstVisibleItemIndex == 0 && activeListState.firstVisibleItemScrollOffset == 0
        }.collect { atTop ->
            if (atTop) {
                onChromeCollapseFractionChange(0f)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = feedBackgroundColor,
        topBar = {
            val topBarContainerModifier = if (topBarHeightPx > 0) {
                Modifier.height(with(density) { collapsedTopBarHeightPx.toDp() })
            } else {
                Modifier
            }
            Box(
                modifier = topBarContainerModifier
                    .clipToBounds()
                    .background(feedBackgroundColor),
            ) {
                Column(
                    modifier = Modifier
                        .then(
                            if (topBarHeightPx > 0) {
                                Modifier.requiredHeight(with(density) { topBarHeightPx.toDp() })
                            } else {
                                Modifier
                            },
                        )
                        .alpha(chromeAlpha)
                        .onSizeChanged { topBarHeightPx = it.height }
                        .background(feedBackgroundColor),
                ) {
                AppTopBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Text(
                                text = topBarTitle,
                                modifier = Modifier.weight(1f, fill = false),
                                color = feedContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { showRelayMenu = true }) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = if (canSelectAllRelays) "フィードメニュー" else "リレー切り替え",
                                    tint = feedContentColor,
                                )
                            }
                            DropdownMenu(
                                expanded = showRelayMenu,
                                onDismissRequest = { showRelayMenu = false },
                            ) {
                                if (canSelectAllRelays) {
                                    DropdownMenuItem(
                                        text = { Text("すべてのリレー") },
                                        onClick = {
                                            followingFeedMode = FollowingFeedMode.Following
                                            RelayStore.setSelectedFollowingRelayUrl(null)
                                            showRelayMenu = false
                                        },
                                        trailingIcon = if (
                                            followingFeedMode == FollowingFeedMode.Following &&
                                            selectedFeedRelayUrl == null
                                        ) {
                                            {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                )
                                            }
                                        } else null,
                                    )
                                    DropdownMenuItem(
                                        text = { Text("リレー設定") },
                                        onClick = {
                                            showRelayMenu = false
                                            onOpenRelaySettings()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("ミュートフィード") },
                                        onClick = {
                                            followingFeedMode = FollowingFeedMode.Muted
                                            RelayStore.setSelectedFollowingRelayUrl(null)
                                            showRelayMenu = false
                                        },
                                        trailingIcon = if (followingFeedMode == FollowingFeedMode.Muted) {
                                            {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                )
                                            }
                                        } else null,
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("リレー設定") },
                                        onClick = {
                                            showRelayMenu = false
                                            onOpenRelaySettings()
                                        },
                                    )
                                    relays.forEach { url ->
                                        DropdownMenuItem(
                                            text = { Text(url.relayDisplayName()) },
                                            onClick = {
                                                RelayStore.setSelectedGlobalRelayUrl(url)
                                                showRelayMenu = false
                                            },
                                            trailingIcon = if (url == selectedFeedRelayUrl) {
                                                {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                    )
                                                }
                                            } else null,
                                        )
                                    }
                                }
                }
            }
        },
                    navigationIcon = {
                        if (authorPubkey == null && ownPubkey != null) {
                            IconButton(onClick = onOpenProfile) {
                                AvatarCircle(
                                    pubkey = ownPubkey,
                                    name = ownProfile?.bestName,
                                    pictureUrl = ownProfile?.picture,
                                    size = 32,
                                )
                            }
                        }
                    },
                    actions = {
                        if (authorPubkey == null) {
                            IconButton(onClick = { onOpenSearch("") }) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "検索",
                                    tint = feedContentColor,
                                )
                            }
                            if (ownPubkey != null) {
                                IconButton(onClick = onOpenNotifications) {
                                    Box(modifier = Modifier.size(24.dp)) {
                                        Icon(
                                            Icons.Default.Notifications,
                                            contentDescription = "通知",
                                            tint = feedContentColor,
                                        )
                                        if (hasNotifications) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(8.dp)
                                                    .background(Color.White, CircleShape)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
                if (authorPubkey == null) {
                    FeedTabRow(
                        tabs = visibleFeedTabs,
                        selectedTab = visibleFeedTab,
                        onTabSelected = { setFeedTab(it) },
                    )
                }
            }
            }
        },
    ) { padding ->
        val timelineModifier = Modifier
            .background(feedBackgroundColor)
            .nestedScroll(chromeNestedScrollConnection)
            .padding(padding)

        if (authorPubkey != null) {
            FeedTimelinePane(
                viewModelKey = "profile-$authorPubkey-${activeRelayUrl ?: "all"}",
                authorPubkey = authorPubkey,
                authorPubkeys = listOf(authorPubkey),
                relayUrl = activeRelayUrl,
                includeRepostsInFeed = false,
                hashtag = null,
                ownPubkey = ownPubkey,
                onUserClick = onUserClick,
                modifier = timelineModifier,
                onReply = onReply,
                onOpenReplies = onOpenReplies,
                onOpenLikes = onOpenLikes,
                onOpenReposts = onOpenReposts,
                onHashtagClick = null,
                scrollToTopRequest = scrollToTopRequest,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = timelineModifier.fillMaxSize(),
            ) { page ->
                when (visibleFeedTabs[page]) {
                    FeedTab.Following -> {
                        val followingAuthors = when (followingFeedMode) {
                            FollowingFeedMode.Following -> followedPubkeys.sorted()
                            FollowingFeedMode.Muted -> mutedPubkeys.sorted()
                        }
                        if (followingFeedMode == FollowingFeedMode.Following && !isFollowListLoaded) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "読み込み中...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        } else {
                            val ownerKey = ownPubkey ?: "anonymous"
                            FeedTimelinePane(
                                viewModelKey = "global-${FeedTab.Following.name}-${followingFeedMode.name}-" +
                                    "all-$ownerKey-${followingAuthors.joinToString(separator = ",")}",
                                authorPubkey = null,
                                authorPubkeys = followingAuthors,
                                relayUrl = null,
                                includeRepostsInFeed = followingFeedMode == FollowingFeedMode.Following,
                                hashtag = null,
                                filterMutedUsers = followingFeedMode == FollowingFeedMode.Following,
                                ownPubkey = ownPubkey,
                                onUserClick = onUserClick,
                                modifier = Modifier.fillMaxSize(),
                                onReply = onReply,
                                onOpenReplies = onOpenReplies,
                                onOpenLikes = onOpenLikes,
                                onOpenReposts = onOpenReposts,
                                onHashtagClick = { tag -> onOpenSearch("#$tag") },
                                listState = followingListState,
                                onRefresh = {
                                    if (followingFeedMode == FollowingFeedMode.Following) {
                                        accountSession?.followRepository?.refresh()
                                    }
                                },
                            )
                        }
                    }

                    FeedTab.Global -> {
                        val ownerKey = ownPubkey ?: "anonymous"
                        FeedTimelinePane(
                            viewModelKey = "global-${FeedTab.Global.name}-" +
                                "${effectiveGlobalRelayUrl ?: "all"}-all-false-$ownerKey",
                            authorPubkey = null,
                            authorPubkeys = null,
                            relayUrl = effectiveGlobalRelayUrl,
                            includeRepostsInFeed = false,
                            hashtag = null,
                            ownPubkey = ownPubkey,
                            onUserClick = onUserClick,
                            modifier = Modifier.fillMaxSize(),
                            onReply = onReply,
                            onOpenReplies = onOpenReplies,
                            onOpenLikes = onOpenLikes,
                            onOpenReposts = onOpenReposts,
                            onHashtagClick = { tag -> onOpenSearch("#$tag") },
                            listState = globalListState,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedTabRow(
    tabs: List<FeedTab>,
    selectedTab: FeedTab,
    onTabSelected: (FeedTab) -> Unit,
) {
    PrimaryTabRow(
        selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = { Text(tab.label) },
            )
        }
    }
}

@Composable
private fun FeedTimelinePane(
    viewModelKey: String,
    authorPubkey: String?,
    authorPubkeys: List<String>?,
    relayUrl: String?,
    includeRepostsInFeed: Boolean,
    hashtag: String?,
    filterMutedUsers: Boolean = true,
    ownPubkey: String?,
    onUserClick: (String) -> Unit,
    modifier: Modifier,
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)?,
    onOpenReplies: (eventId: String) -> Unit,
    onOpenLikes: (eventId: String) -> Unit,
    onOpenReposts: (eventId: String) -> Unit,
    onHashtagClick: ((tag: String) -> Unit)?,
    scrollToTopRequest: Int = 0,
    listState: LazyListState? = null,
    onRefresh: (() -> Unit)? = null,
) {
    val viewModel: FeedViewModel = accountSessionViewModel(
        key = viewModelKey,
    ) { accountSession ->
        FeedViewModel(
            accountSession = accountSession,
            authorPubkey = authorPubkey,
            authorPubkeys = authorPubkeys,
            relayUrl = relayUrl,
            includeRepostsInFeed = includeRepostsInFeed,
            hashtag = hashtag,
            filterMutedUsers = filterMutedUsers,
        )
    }
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.engagementError) {
        val error = state.engagementError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.consumeEngagementError()
    }

    LaunchedEffect(viewModel) {
        viewModel.startSubscriptions()
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stopSubscriptions()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NoteTimeline(
            state = state,
            ownPubkey = ownPubkey,
            onUserClick = onUserClick,
            onLoadMore = viewModel::loadMore,
            onLike = viewModel::react,
            onUnlike = viewModel::unreact,
            onEmojiReact = viewModel::reactWithEmoji,
            onEmojiUnreact = viewModel::unreactWithEmoji,
            onDelete = viewModel::deleteEvent,
            modifier = modifier,
            onReply = onReply,
            onOpenReplies = onOpenReplies,
            onOpenLikes = onOpenLikes,
            onOpenReposts = onOpenReposts,
            onRepost = viewModel::repost,
            onUnrepost = viewModel::unrepost,
            onReport = viewModel::reportEvent,
            onHashtagClick = onHashtagClick,
            scrollToTopRequest = scrollToTopRequest,
            listState = listState,
            isRefreshing = state.isRefreshing,
            onRefresh = {
                onRefresh?.invoke()
                viewModel.refresh()
            },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

enum class FeedTab(val label: String) {
    Following("フォロー"),
    Global("グローバル"),
}

private enum class FollowingFeedMode {
    Following,
    Muted,
}
private const val ChromeSettleDelayMillis = 60L
private const val ChromeSettleAnimationMillis = 140

private fun String.relayDisplayName(): String =
    removePrefix("wss://").removePrefix("ws://").trimEnd('/')
