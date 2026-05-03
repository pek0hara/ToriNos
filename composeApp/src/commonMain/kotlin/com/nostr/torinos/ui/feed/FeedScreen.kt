package com.nostr.torinos.ui.feed

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    onUserClick: (pubkey: String) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    ownPubkey: String? = null,
    ownProfile: NostrProfile? = null,
    scrollToTopRequest: Int = 0,
    scrollToTopTargetTab: FeedTab = FeedTab.Following,
    onCurrentFeedTabChanged: (FeedTab) -> Unit = {},
    followingListState: LazyListState? = null,
    globalListState: LazyListState? = null,
    /** null = グローバルフィード、非null = 特定ユーザーのポスト */
    authorPubkey: String? = null,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedRelayUrl.collectAsState()
    val followedPubkeys by FollowRepository.followedPubkeys.collectAsState()
    var showRelayMenu by remember { mutableStateOf(false) }
    var feedTab by rememberSaveable { mutableStateOf(FeedTab.Following) }
    var handledScrollToTopRequest by remember { mutableStateOf(scrollToTopRequest) }
    var isHashtagFilterEditing by rememberSaveable { mutableStateOf(false) }
    var hashtagDraft by rememberSaveable { mutableStateOf("") }
    var activeHashtag by rememberSaveable { mutableStateOf<String?>(null) }
    val hashtagFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun setFeedTab(tab: FeedTab) {
        feedTab = tab
        onCurrentFeedTabChanged(tab)
    }

    fun applyHashtagFilter() {
        val tag = hashtagDraft.normalizedHashtag()
        activeHashtag = tag
        hashtagDraft = tag.orEmpty()
        isHashtagFilterEditing = tag != null
        keyboardController?.hide()
    }

    fun clearHashtagFilter() {
        activeHashtag = null
        hashtagDraft = ""
        isHashtagFilterEditing = false
        keyboardController?.hide()
    }

    fun selectHashtagFilter(tag: String) {
        val normalized = tag.normalizedHashtag() ?: return
        activeHashtag = normalized
        hashtagDraft = normalized
        isHashtagFilterEditing = true
    }

    LaunchedEffect(Unit) {
        onCurrentFeedTabChanged(feedTab)
    }

    // リレーリストが変わったら選択中 URL を有効なものに補正
    LaunchedEffect(relays, selectedRelayUrl) {
        if (selectedRelayUrl == null || selectedRelayUrl !in relays) {
            RelayStore.setSelectedRelayUrl(relays.firstOrNull())
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

    val activeRelayUrl = selectedRelayUrl
    val activeHashtagFilter = activeHashtag

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (isHashtagFilterEditing) {
                            LaunchedEffect(Unit) {
                                hashtagFocusRequester.requestFocus()
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start,
                            ) {
                                Text(
                                    text = "#",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                BasicTextField(
                                    value = hashtagDraft,
                                    onValueChange = { hashtagDraft = it.sanitizedHashtagInput() },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.titleMedium.copy(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onPrimary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { applyHashtagFilter() }),
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(hashtagFocusRequester),
                                    decorationBox = { innerTextField ->
                                        if (hashtagDraft.isBlank()) {
                                            Text(
                                                text = "ハッシュタグ",
                                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                                maxLines = 1,
                                            )
                                        }
                                        innerTextField()
                                    },
                                )
                                IconButton(onClick = { clearHashtagFilter() }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "ハッシュタグフィルターを解除",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start,
                            ) {
                                Text(
                                    text = selectedRelayUrl?.relayDisplayName() ?: "—",
                                    modifier = Modifier.weight(1f, fill = false),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
                            IconButton(
                                onClick = {
                                    if (isHashtagFilterEditing) {
                                        applyHashtagFilter()
                                    } else {
                                        hashtagDraft = activeHashtag.orEmpty()
                                        isHashtagFilterEditing = true
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Default.Tag,
                                    contentDescription = "ハッシュタグフィルター",
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
                enabled = authorPubkey == null,
                currentTab = feedTab,
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

            feedTab == FeedTab.Following -> {
                key(FeedTab.Following) {
                    val followingAuthors = followedPubkeys.sorted()
                    FeedTimelinePane(
                        viewModelKey = "global-${FeedTab.Following.name}-${activeRelayUrl ?: "all"}-" +
                            "${followingAuthors.joinToString(separator = ",")}-true-${activeHashtagFilter ?: "none"}",
                        authorPubkey = null,
                        authorPubkeys = followingAuthors,
                        relayUrl = activeRelayUrl,
                        includeRepostsInFeed = true,
                        hashtag = activeHashtagFilter,
                        ownPubkey = ownPubkey,
                        onUserClick = onUserClick,
                        modifier = timelineModifier,
                        onReply = onReply,
                        onOpenReplies = onOpenReplies,
                        onOpenLikes = onOpenLikes,
                        onOpenReposts = onOpenReposts,
                        onHashtagClick = ::selectHashtagFilter,
                        listState = followingListState,
                    )
                }
            }

            else -> {
                key(FeedTab.Global) {
                    FeedTimelinePane(
                        viewModelKey = "global-${FeedTab.Global.name}-${activeRelayUrl ?: "all"}-all-false-" +
                            "${activeHashtagFilter ?: "none"}",
                        authorPubkey = null,
                        authorPubkeys = null,
                        relayUrl = activeRelayUrl,
                        includeRepostsInFeed = false,
                        hashtag = activeHashtagFilter,
                        ownPubkey = ownPubkey,
                        onUserClick = onUserClick,
                        modifier = timelineModifier,
                        onReply = onReply,
                        onOpenReplies = onOpenReplies,
                        onOpenLikes = onOpenLikes,
                        onOpenReposts = onOpenReposts,
                        onHashtagClick = ::selectHashtagFilter,
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

    DisposableEffect(viewModel) {
        viewModel.startSubscriptions()
        onDispose {
            viewModel.stopSubscriptions()
        }
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

private fun String.sanitizedHashtagInput(): String =
    trimStart()
        .removePrefix("#")
        .filterNot { it.isWhitespace() }

private fun String.normalizedHashtag(): String? =
    sanitizedHashtagInput()
        .trim('#')
        .lowercase()
        .takeIf { it.isNotBlank() }
