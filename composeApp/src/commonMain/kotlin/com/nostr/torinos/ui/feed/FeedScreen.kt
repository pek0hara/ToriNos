package com.nostr.torinos.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NoteTimeline
import com.nostr.torinos.ui.profile.AvatarCircle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenSettings: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    onOpenSearch: (query: String) -> Unit = {},
    ownPubkey: String? = null,
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
    /** null = グローバルフィード、非null = 特定ユーザーのポスト */
    authorPubkey: String? = null,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedFollowingRelayUrl by RelayStore.selectedFollowingRelayUrl.collectAsState()
    val selectedGlobalRelayUrl by RelayStore.selectedGlobalRelayUrl.collectAsState()
    val followedPubkeys by FollowRepository.followedPubkeys.collectAsState()
    var showRelayMenu by remember { mutableStateOf(false) }
    var feedTab by rememberSaveable { mutableStateOf(FeedTab.Following) }
    var handledScrollToTopRequest by remember { mutableStateOf(scrollToTopRequest) }
    val isLoggedOutMainFeed = authorPubkey == null && isAccountLoaded && ownPubkey == null
    val visibleFeedTabs = if (isLoggedOutMainFeed) listOf(FeedTab.Global) else FeedTab.entries
    val visibleFeedTab = if (feedTab in visibleFeedTabs) feedTab else FeedTab.Global

    fun setFeedTab(tab: FeedTab) {
        val nextTab = if (isLoggedOutMainFeed && tab == FeedTab.Following) FeedTab.Global else tab
        feedTab = nextTab
        onCurrentFeedTabChanged(nextTab)
    }

    LaunchedEffect(Unit) {
        onCurrentFeedTabChanged(visibleFeedTab)
    }

    LaunchedEffect(isLoggedOutMainFeed) {
        if (isLoggedOutMainFeed && feedTab == FeedTab.Following) {
            setFeedTab(FeedTab.Global)
        }
    }

    LaunchedEffect(feedTabChangeRequest) {
        if (feedTabChangeRequest > 0 && authorPubkey == null) {
            setFeedTab(requestedFeedTab)
        }
    }

    // リレーリストが変わったら、各タブの選択中 URL を有効なものに補正する。
    LaunchedEffect(relays, selectedFollowingRelayUrl, selectedGlobalRelayUrl) {
        if (selectedFollowingRelayUrl != null && selectedFollowingRelayUrl !in relays) {
            RelayStore.setSelectedFollowingRelayUrl(null)
        }
        if (selectedGlobalRelayUrl == null || selectedGlobalRelayUrl !in relays) {
            RelayStore.setSelectedGlobalRelayUrl(relays.firstOrNull())
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
        authorPubkey != null -> selectedGlobalRelayUrl
        visibleFeedTab == FeedTab.Following -> selectedFollowingRelayUrl
        else -> selectedGlobalRelayUrl
    }
    val canSelectAllRelays = authorPubkey == null && visibleFeedTab == FeedTab.Following
    val activeRelayUrl = selectedFeedRelayUrl
    val feedBackgroundColor = MaterialTheme.colorScheme.background
    val feedContentColor = MaterialTheme.colorScheme.onBackground

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(modifier = Modifier.background(feedBackgroundColor)) {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Text(
                                text = selectedFeedRelayUrl?.relayDisplayName()
                                    ?: if (canSelectAllRelays) "すべてのリレー" else "—",
                                modifier = Modifier.weight(1f, fill = false),
                                color = feedContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { showRelayMenu = true }) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "リレー切り替え",
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
                                            RelayStore.setSelectedFollowingRelayUrl(null)
                                            showRelayMenu = false
                                        },
                                        trailingIcon = if (selectedFeedRelayUrl == null) {
                                            {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                )
                                            }
                                        } else null,
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("リレー設定") },
                                    onClick = {
                                        showRelayMenu = false
                                        onOpenSettings()
                                    },
                                )
                                relays.forEach { url ->
                                    DropdownMenuItem(
                                        text = { Text(url.relayDisplayName()) },
                                        onClick = {
                                            if (canSelectAllRelays) {
                                                RelayStore.setSelectedFollowingRelayUrl(url)
                                            } else {
                                                RelayStore.setSelectedGlobalRelayUrl(url)
                                            }
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = feedBackgroundColor,
                        scrolledContainerColor = feedBackgroundColor,
                        titleContentColor = feedContentColor,
                        actionIconContentColor = feedContentColor,
                        navigationIconContentColor = feedContentColor,
                    ),
                )
                if (authorPubkey == null) {
                    PrimaryTabRow(
                        selectedTabIndex = visibleFeedTabs.indexOf(visibleFeedTab).coerceAtLeast(0),
                        containerColor = feedBackgroundColor,
                        contentColor = feedContentColor,
                    ) {
                        visibleFeedTabs.forEach { tab ->
                            Tab(
                                selected = visibleFeedTab == tab,
                                onClick = { setFeedTab(tab) },
                                text = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        val timelineModifier = Modifier
            .padding(padding)
            .feedTabSwipe(
                enabled = authorPubkey == null && visibleFeedTabs.size > 1,
                currentTab = visibleFeedTab,
                onTabChange = { setFeedTab(it) },
            )

        when {
            authorPubkey != null -> {
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
            }

            visibleFeedTab == FeedTab.Following -> {
                key(FeedTab.Following) {
                    val followingAuthors = followedPubkeys.sorted()
                    val ownerKey = ownPubkey ?: "anonymous"
                    FeedTimelinePane(
                        viewModelKey = "global-${FeedTab.Following.name}-${activeRelayUrl ?: "all"}-" +
                            "$ownerKey-${followingAuthors.joinToString(separator = ",")}-true",
                        authorPubkey = null,
                        authorPubkeys = followingAuthors,
                        relayUrl = activeRelayUrl,
                        includeRepostsInFeed = true,
                        hashtag = null,
                        ownPubkey = ownPubkey,
                        onUserClick = onUserClick,
                        modifier = timelineModifier,
                        onReply = onReply,
                        onOpenReplies = onOpenReplies,
                        onOpenLikes = onOpenLikes,
                        onOpenReposts = onOpenReposts,
                        onHashtagClick = { tag -> onOpenSearch("#$tag") },
                        listState = followingListState,
                    )
                }
            }

            else -> {
                key(FeedTab.Global) {
                    val ownerKey = ownPubkey ?: "anonymous"
                    FeedTimelinePane(
                        viewModelKey = "global-${FeedTab.Global.name}-${activeRelayUrl ?: "all"}-all-false-" +
                            ownerKey,
                        authorPubkey = null,
                        authorPubkeys = null,
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
                        onHashtagClick = { tag -> onOpenSearch("#$tag") },
                        listState = globalListState,
                    )
                }
            }
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
) {
    val viewModel: FeedViewModel = viewModel(key = viewModelKey) {
        FeedViewModel(
            authorPubkey = authorPubkey,
            authorPubkeys = authorPubkeys,
            relayUrl = relayUrl,
            includeRepostsInFeed = includeRepostsInFeed,
            hashtag = hashtag,
        )
    }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.startSubscriptions()
    }

    NoteTimeline(
        state = state,
        ownPubkey = ownPubkey,
        onUserClick = onUserClick,
        onLoadMore = viewModel::loadMore,
        onLike = viewModel::react,
        onUnlike = viewModel::unreact,
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
    )
}

enum class FeedTab(val label: String) {
    Following("フォロー"),
    Global("グローバル"),
}

private fun Modifier.feedTabSwipe(
    enabled: Boolean,
    currentTab: FeedTab,
    onTabChange: (FeedTab) -> Unit,
): Modifier {
    if (!enabled) return this

    return pointerInput(currentTab) {
        var dragAmount = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragAmount = 0f },
            onHorizontalDrag = { change, amount ->
                dragAmount += amount
                change.consume()
            },
            onDragEnd = {
                when {
                    dragAmount < -SwipeThresholdPx && currentTab == FeedTab.Following ->
                        onTabChange(FeedTab.Global)
                    dragAmount > SwipeThresholdPx && currentTab == FeedTab.Global ->
                        onTabChange(FeedTab.Following)
                }
            },
            onDragCancel = { dragAmount = 0f },
        )
    }
}

private const val SwipeThresholdPx = 80f

private fun String.relayDisplayName(): String =
    removePrefix("wss://").removePrefix("ws://").trimEnd('/')
