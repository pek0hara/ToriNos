package com.nostr.torinos.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.nostr.torinos.ui.components.AppMessageComposer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.model.noteContextForChannel
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.NoteCard
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.QuotedEvent
import com.nostr.torinos.ui.components.rememberSyncedTextFieldValue
import com.nostr.torinos.ui.components.stripImageUrls
import com.nostr.torinos.ui.profile.AvatarCircle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    eventId: String,
    initialTab: String = "auto",
    channelId: String? = null,
    onBack: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String, preview: String, channelId: String?) -> Unit)? = null,
    onOpenThread: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    ownPubkey: String? = null,
    viewModel: ThreadViewModel = accountSessionViewModel(
        key = "thread-$eventId-${channelId ?: "note"}",
    ) { accountSession ->
        ThreadViewModel(
            eventId,
            noteContextForChannel(channelId),
            accountSession = accountSession,
        )
    },
) {
    val noteContext = remember(channelId) { noteContextForChannel(channelId) }
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember(initialTab) { mutableStateOf(ThreadTab.fromRouteValue(initialTab)) }
    var didSelectTabManually by remember(eventId) { mutableStateOf(false) }
    val listState = rememberSaveable(eventId, saver = LazyListState.Saver) { LazyListState() }
    var didApplyInitialBottomScroll by remember(eventId) { mutableStateOf(false) }
    var previousRepliesBottomIndex by remember(eventId) { mutableStateOf<Int?>(null) }

    DisposableEffect(viewModel) {
        viewModel.startSubscriptions()
        onDispose { viewModel.stopSubscriptions() }
    }

    LaunchedEffect(state.engagementError) {
        val error = state.engagementError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.consumeEngagementError()
    }

    LaunchedEffect(
        initialTab,
        didSelectTabManually,
        state.replies.size,
        state.repostCount,
        state.reactionPubkeys.size,
    ) {
        if (initialTab == AutoTabRouteValue && !didSelectTabManually) {
            selectedTab = preferredThreadTab(
                replyCount = state.replies.size,
                repostCount = state.repostCount,
                reactionCount = state.reactionPubkeys.size,
            )
        }
    }

    LaunchedEffect(eventId, selectedTab, state.root?.id, state.replies.size) {
        if (selectedTab != ThreadTab.Replies) {
            previousRepliesBottomIndex = null
            return@LaunchedEffect
        }
        state.root ?: return@LaunchedEffect

        val bottomIndex = RootItemCount + TabRowItemCount + maxOf(state.replies.size, 1) - 1
        val visibleLastIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        val wasFollowingBottom = previousRepliesBottomIndex == null ||
            visibleLastIndex == previousRepliesBottomIndex ||
            !listState.canScrollForward

        if (!didApplyInitialBottomScroll || wasFollowingBottom) {
            listState.scrollToItem(bottomIndex)
            didApplyInitialBottomScroll = true
        }
        previousRepliesBottomIndex = bottomIndex
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = { Text("ポスト詳細") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.root != null && ownPubkey != null) {
                ThreadReplyInputBar(
                    text = state.replyText,
                    isPosting = state.isReplying,
                    error = state.replyError,
                    onTextChange = viewModel::onReplyTextChange,
                    onSend = viewModel::submitReply,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading && state.root == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "スレッドを読み込み中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.root == null -> {
                    Text(
                        text = "ポストを読み込めませんでした",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .threadTabSwipe(
                                currentTab = selectedTab,
                                onTabChange = {
                                    didSelectTabManually = true
                                    selectedTab = it
                                },
                            ),
                    ) {
                        item {
                            val root = state.root ?: return@item
                            val replyParentId = noteContext.replyTargetId(root)
                            NoteCard(
                                event = root,
                                profile = state.profiles[root.pubkey],
                                profiles = state.profiles,
                                replyParent = replyParentId?.let { parentId ->
                                    state.quotedEvents[parentId]?.let { parentEvent ->
                                        QuotedEvent(
                                            event = parentEvent,
                                            profile = state.profiles[parentEvent.pubkey],
                                        )
                                    }
                                },
                                quotedEvents = quotedEventIds(root)
                                    .filter { it != replyParentId }
                                    .mapNotNull { quotedEventId ->
                                        state.quotedEvents[quotedEventId]?.let { quotedEvent ->
                                            QuotedEvent(
                                                event = quotedEvent,
                                                profile = state.profiles[quotedEvent.pubkey],
                                            )
                                        }
                                    },
                                replyCount = state.replyCounts[root.id] ?: state.replies.size,
                                replies = state.replies,
                                reactionCount = state.reactionCounts[root.id] ?: state.reactionPubkeys.size,
                                likeReactionCount = state.likeReactionCounts[root.id] ?: 0,
                                customReactions = state.customReactions[root.id].orEmpty(),
                                unicodeReactions = state.unicodeReactions[root.id].orEmpty(),
                                repostCount = state.repostCount,
                                isLiked = state.isLiked(root.id),
                                ownEmojiReactionEventIds = state.displayOwnEmojiReactionEventIds(root.id),
                                isReposted = state.isRootReposted(root.id),
                                onUserClick = onUserClick,
                                onLike = if (ownPubkey != null) {
                                    {
                                        if (state.isLiked(root.id)) {
                                            viewModel.unreact(root.id)
                                        } else {
                                            viewModel.react(root.id, root.pubkey)
                                        }
                                    }
                                } else null,
                                onEmojiReact = if (ownPubkey != null) {
                                    { option -> viewModel.reactWithEmoji(root.id, root.pubkey, option) }
                                } else null,
                                onEmojiUnreact = if (ownPubkey != null) {
                                    { option -> viewModel.unreactWithEmoji(root.id, option) }
                                } else null,
                                onReply = if (ownPubkey != null && onReply != null) {
                                    { onReply(root.id, root.pubkey, root.content.replyPreviewText(), channelId) }
                                } else null,
                                onOpenReplies = {
                                    didSelectTabManually = true
                                    selectedTab = ThreadTab.Replies
                                },
                                onOpenLikes = {
                                    didSelectTabManually = true
                                    selectedTab = ThreadTab.Likes
                                },
                                onOpenReposts = {
                                    didSelectTabManually = true
                                    selectedTab = ThreadTab.Reposts
                                },
                                onRepost = if (ownPubkey != null) {
                                    {
                                        if (state.isRootReposted(root.id)) {
                                            viewModel.unrepost()
                                        } else {
                                            viewModel.repost(root)
                                        }
                                    }
                                } else null,
                                ownPubkey = ownPubkey,
                            )
                            HorizontalDivider()
                        }
                        item {
                            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                                ThreadTab.entries.forEach { tab ->
                                    Tab(
                                        selected = selectedTab == tab,
                                        onClick = {
                                            didSelectTabManually = true
                                            selectedTab = tab
                                        },
                                        text = { Text(tab.label) },
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                        when (selectedTab) {
                            ThreadTab.Replies -> {
                                if (state.replies.isEmpty()) {
                                    emptyTabItem("返信はまだありません")
                                } else {
                                    items(state.replies, key = { it.id }) { reply ->
                                        val replyCount = state.replyCounts[reply.id] ?: 0
                                        val replyParentId = noteContext.replyTargetId(reply)
                                        NoteCard(
                                            event = reply,
                                            profile = state.profiles[reply.pubkey],
                                            profiles = state.profiles,
                                            replyParent = replyParentId?.let { parentId ->
                                                state.quotedEvents[parentId]?.let { parentEvent ->
                                                    QuotedEvent(
                                                        event = parentEvent,
                                                        profile = state.profiles[parentEvent.pubkey],
                                                    )
                                                }
                                            },
                                            quotedEvents = quotedEventIds(reply)
                                                .filter { it != replyParentId }
                                                .mapNotNull { quotedEventId ->
                                                    state.quotedEvents[quotedEventId]?.let { quotedEvent ->
                                                        QuotedEvent(
                                                            event = quotedEvent,
                                                            profile = state.profiles[quotedEvent.pubkey],
                                                        )
                                                    }
                                                },
                                            replyCount = replyCount,
                                            replies = state.repliesByEventId[reply.id].orEmpty(),
                                            reactionCount = state.reactionCounts[reply.id] ?: 0,
                                            likeReactionCount = state.likeReactionCounts[reply.id] ?: 0,
                                            customReactions = state.customReactions[reply.id].orEmpty(),
                                            unicodeReactions = state.unicodeReactions[reply.id].orEmpty(),
                                            isLiked = state.isLiked(reply.id),
                                            ownEmojiReactionEventIds = state.displayOwnEmojiReactionEventIds(reply.id),
                                            onUserClick = onUserClick,
                                            onLike = if (ownPubkey != null) {
                                                {
                                                    if (state.isLiked(reply.id)) {
                                                        viewModel.unreact(reply.id)
                                                    } else {
                                                        viewModel.react(reply.id, reply.pubkey)
                                                    }
                                                }
                                            } else null,
                                            onEmojiReact = if (ownPubkey != null) {
                                                { option -> viewModel.reactWithEmoji(reply.id, reply.pubkey, option) }
                                            } else null,
                                            onEmojiUnreact = if (ownPubkey != null) {
                                                { option -> viewModel.unreactWithEmoji(reply.id, option) }
                                            } else null,
                                            onReply = if (ownPubkey != null && onReply != null) {
                                                { onReply(reply.id, reply.pubkey, reply.content.replyPreviewText(), channelId) }
                                            } else null,
                                            onOpenReplies = { onOpenThread(reply.id) },
                                            onOpenLikes = { onOpenLikes(reply.id) },
                                            onOpenReposts = { onOpenReposts(reply.id) },
                                            onNoteClick = onOpenThread,
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                            ThreadTab.Likes -> {
                                if (state.reactionPubkeys.isEmpty()) {
                                    emptyTabItem("いいねはまだありません")
                                } else {
                                    items(state.reactionPubkeys, key = { it }) { pubkey ->
                                        ReactionUserRow(
                                            pubkey = pubkey,
                                            profile = state.profiles[pubkey],
                                            reaction = state.rootReactionsByPubkey[pubkey],
                                            showReaction = true,
                                            onClick = { onUserClick(pubkey) },
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                            ThreadTab.Reposts -> {
                                if (state.repostPubkeys.isEmpty() && state.quoteReposts.isEmpty()) {
                                    emptyTabItem("リポストはまだありません")
                                } else {
                                    items(state.repostPubkeys, key = { it }) { pubkey ->
                                        ReactionUserRow(
                                            pubkey = pubkey,
                                            profile = state.profiles[pubkey],
                                            onClick = { onUserClick(pubkey) },
                                        )
                                        HorizontalDivider()
                                    }
                                    items(state.quoteReposts, key = { it.id }) { quoteRepost ->
                                        val quotedRoot = state.root?.let { root ->
                                            QuotedEvent(
                                                event = root,
                                                profile = state.profiles[root.pubkey],
                                            )
                                        }
                                        NoteCard(
                                            event = quoteRepost,
                                            profile = state.profiles[quoteRepost.pubkey],
                                            profiles = state.profiles,
                                            replyCount = state.replyCounts[quoteRepost.id] ?: 0,
                                            replies = state.repliesByEventId[quoteRepost.id].orEmpty(),
                                            reactionCount = state.reactionCounts[quoteRepost.id] ?: 0,
                                            likeReactionCount = state.likeReactionCounts[quoteRepost.id] ?: 0,
                                            customReactions = state.customReactions[quoteRepost.id].orEmpty(),
                                            unicodeReactions = state.unicodeReactions[quoteRepost.id].orEmpty(),
                                            quotedEvents = listOfNotNull(quotedRoot),
                                            onUserClick = onUserClick,
                                            onReply = if (ownPubkey != null && onReply != null) {
                                                {
                                                    onReply(
                                                        quoteRepost.id,
                                                        quoteRepost.pubkey,
                                                        quoteRepost.content.replyPreviewText(),
                                                        channelId,
                                                    )
                                                }
                                            } else null,
                                            onOpenReplies = { onOpenThread(quoteRepost.id) },
                                            onOpenLikes = { onOpenLikes(quoteRepost.id) },
                                            onOpenReposts = { onOpenReposts(quoteRepost.id) },
                                            onNoteClick = onOpenThread,
                                            ownPubkey = ownPubkey,
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ThreadTab(val routeValue: String, val label: String) {
    Replies("replies", "返信"),
    Reposts("reposts", "リポスト"),
    Likes("likes", "いいね");

    companion object {
        fun fromRouteValue(value: String): ThreadTab =
            entries.firstOrNull { it.routeValue == value } ?: Replies
    }
}

private fun preferredThreadTab(
    replyCount: Int,
    repostCount: Int,
    reactionCount: Int,
): ThreadTab = when {
    replyCount > 0 -> ThreadTab.Replies
    repostCount > 0 -> ThreadTab.Reposts
    reactionCount > 0 -> ThreadTab.Likes
    else -> ThreadTab.Replies
}

private fun Modifier.threadTabSwipe(
    currentTab: ThreadTab,
    onTabChange: (ThreadTab) -> Unit,
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
                dragAmount < -SwipeThresholdPx -> currentTab.next()?.let(onTabChange)
                dragAmount > SwipeThresholdPx -> currentTab.previous()?.let(onTabChange)
            }
        },
        onDragCancel = { dragAmount = 0f },
    )
}

private fun ThreadTab.next(): ThreadTab? =
    ThreadTab.entries.getOrNull(ordinal + 1)

private fun ThreadTab.previous(): ThreadTab? =
    ThreadTab.entries.getOrNull(ordinal - 1)

private fun String.replyPreviewText(): String =
    stripImageUrls(stripNostrEventUris(this))
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(160)

private const val SwipeThresholdPx = 80f
private const val AutoTabRouteValue = "auto"
private const val RootItemCount = 1
private const val TabRowItemCount = 1

private fun androidx.compose.foundation.lazy.LazyListScope.emptyTabItem(text: String) {
    item {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ThreadReplyInputBar(
    text: String,
    isPosting: Boolean,
    error: String?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    AppMessageComposer(
        text = text,
        onTextChange = onTextChange,
        onSend = onSend,
        placeholder = "返信を追加…",
        isSending = isPosting,
        error = error,
    )
}

@Composable
private fun ReactionUserRow(
    pubkey: String,
    profile: com.nostr.torinos.model.NostrProfile?,
    reaction: com.nostr.torinos.model.NostrEvent? = null,
    showReaction: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(
            pubkey = pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 44,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ProfileNameText(
                profile = profile,
                fallback = pubkey.take(8) + "…" + pubkey.takeLast(8),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val subText = profile?.nip05?.takeIf { it.isNotBlank() }
                ?: profile?.about?.takeIf { it.isNotBlank() }
            if (subText != null) {
                Text(
                    text = subText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (reaction != null) {
            ReactionValue(reaction)
        } else if (showReaction) {
            Text(
                text = "❤️",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ReactionValue(reaction: com.nostr.torinos.model.NostrEvent) {
    val customReaction = remember(reaction.id) { reaction.toCustomReaction() }
    if (customReaction != null) {
        NetworkImage(
            url = customReaction.imageUrl,
            contentDescription = ":${customReaction.shortcode}:",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(28.dp),
        )
    } else {
        Text(
            text = when (val content = reaction.content.trim()) {
                "", "+" -> "❤️"
                "-" -> "👎"
                else -> content
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}
