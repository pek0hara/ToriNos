package com.nostr.torinos.ui.feed

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onOpenSearch: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    ownPubkey: String? = null,
    ownProfile: NostrProfile? = null,
    scrollToTopRequest: Int = 0,
    /** null = グローバルフィード、非null = 特定ユーザーの投稿 */
    authorPubkey: String? = null,
) {
    val relays by RelayStore.relays.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedRelayUrl by RelayStore.selectedRelayUrl.collectAsStateWithLifecycle()
    val followedPubkeys by FollowRepository.followedPubkeys.collectAsStateWithLifecycle()
    var showRelayMenu by remember { mutableStateOf(false) }
    var feedTab by remember { mutableStateOf(FeedTab.Following) }

    // リレーリストが変わったら選択中 URL を有効なものに補正
    LaunchedEffect(relays, selectedRelayUrl) {
        if (selectedRelayUrl == null || selectedRelayUrl !in relays) {
            RelayStore.setSelectedRelayUrl(relays.firstOrNull())
        }
    }

    val activeAuthorPubkeys = when {
        authorPubkey != null -> listOf(authorPubkey)
        feedTab == FeedTab.Following -> followedPubkeys.toList()
        else -> null
    }
    val activeRelayUrl = selectedRelayUrl
    val includeRepostsInFeed = authorPubkey == null && feedTab == FeedTab.Following

    val viewModel: FeedViewModel = viewModel(
        key = "${authorPubkey ?: "global"}-${feedTab.name}-${activeRelayUrl ?: "all"}-${activeAuthorPubkeys?.hashCode() ?: "all"}-$includeRepostsInFeed",
    ) {
        FeedViewModel(
            authorPubkey = authorPubkey,
            authorPubkeys = activeAuthorPubkeys,
            relayUrl = activeRelayUrl,
            includeRepostsInFeed = includeRepostsInFeed,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        viewModel.startSubscriptions()
        onDispose {
            viewModel.stopSubscriptions()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Text(
                                text = selectedRelayUrl?.relayDisplayName() ?: "—",
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            IconButton(onClick = { showRelayMenu = true }) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "リレー切り替え",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            DropdownMenu(
                                expanded = showRelayMenu,
                                onDismissRequest = { showRelayMenu = false },
                            ) {
                                relays.forEach { url ->
                                    DropdownMenuItem(
                                        text = { Text(url.relayDisplayName()) },
                                        onClick = {
                                            RelayStore.setSelectedRelayUrl(url)
                                            showRelayMenu = false
                                        },
                                        trailingIcon = if (url == selectedRelayUrl) {
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
                            IconButton(onClick = onOpenSearch) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "検索",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "リレー設定",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
                if (authorPubkey == null) {
                    PrimaryTabRow(selectedTabIndex = feedTab.ordinal) {
                        FeedTab.entries.forEach { tab ->
                            Tab(
                                selected = feedTab == tab,
                                onClick = { feedTab = tab },
                                text = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NoteTimeline(
            state = state,
            ownPubkey = ownPubkey,
            onUserClick = onUserClick,
            onLoadMore = viewModel::loadMore,
            onLike = viewModel::react,
            onUnlike = viewModel::unreact,
            onDelete = viewModel::deleteEvent,
            modifier = Modifier
                .padding(padding)
                .feedTabSwipe(
                    enabled = authorPubkey == null,
                    currentTab = feedTab,
                    onTabChange = { feedTab = it },
                ),
            onReply = onReply,
            onOpenReplies = onOpenReplies,
            onOpenLikes = onOpenLikes,
            onOpenReposts = onOpenReposts,
            onRepost = viewModel::repost,
            onUnrepost = viewModel::unrepost,
            scrollToTopRequest = scrollToTopRequest,
        )
    }
}

private enum class FeedTab(val label: String) {
    Following("フォロー"),
    AllRelays("全リレー"),
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
                        onTabChange(FeedTab.AllRelays)
                    dragAmount > SwipeThresholdPx && currentTab == FeedTab.AllRelays ->
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
