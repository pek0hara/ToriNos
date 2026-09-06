package com.nostr.torinos.ui.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.AppMessageComposer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LazyListScrollbar
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.NoteCard
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.rememberSyncedTextFieldValue
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.ui.components.stripImageUrls
import com.nostr.torinos.ui.profile.AvatarCircle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    channelId: String,
    onBack: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String, preview: String, channelId: String) -> Unit)? = null,
    onOpenThread: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    ownPubkey: String? = null,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedChannelRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()

    LaunchedEffect(relays, selectedRelayUrl) {
        if (selectedRelayUrl == null || selectedRelayUrl !in relays) {
            RelayStore.setSelectedChannelRelayUrl(relays.firstOrNull())
        }
    }

    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        ChannelDetailRelayPendingContent(
            isLoaded = isRelayStoreLoaded,
            hasEnabledRelays = relays.isNotEmpty(),
            onBack = onBack,
        )
        return
    }

    val viewModel: ChannelViewModel = accountSessionViewModel(
        key = "$channelId-$activeRelayUrl",
    ) { accountSession ->
        ChannelViewModel(
            channelId = channelId,
            relayUrl = activeRelayUrl,
            accountSession = accountSession,
        )
    }
    val state by viewModel.state.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val isForeground = lifecycleState == Lifecycle.State.RESUMED
    val snackbarHostState = remember { SnackbarHostState() }
    val mutedPubkeys = LocalAccountSession.current?.muteStore?.mutedPubkeys
        ?.collectAsState()?.value.orEmpty()
    val listState = remember(channelId, selectedRelayUrl) { LazyListState() }
    var showThreadInfoDialog by remember(channelId, selectedRelayUrl) { mutableStateOf(false) }

    LaunchedEffect((state as? ChannelViewModel.UiState.Ready)?.engagementError) {
        val error = (state as? ChannelViewModel.UiState.Ready)?.engagementError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.consumeEngagementError()
    }

    val history = (state as? ChannelViewModel.UiState.Ready)?.history
    var navigating by remember(viewModel) { mutableStateOf(false) }
    var userHasScrolled by remember(viewModel) { mutableStateOf(false) }
    var highlightedId by remember(viewModel) { mutableStateOf<String?>(null) }
    var atLatest by remember(viewModel) { mutableStateOf(true) }

    LaunchedEffect(viewModel, listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !navigating && (state as? ChannelViewModel.UiState.Ready)?.history?.navigation == null) {
                userHasScrolled = true
            }
        }
    }
    val newestVisibleMessageId = (state as? ChannelViewModel.UiState.Ready)?.messages?.firstOrNull()?.id
    LaunchedEffect(viewModel, listState, newestVisibleMessageId) {
        snapshotFlow {
            val layout = listState.layoutInfo
            isChannelLatestVisible(
                newestMessageId = newestVisibleMessageId,
                visibleKeys = layout.visibleItemsInfo.map { it.key },
                totalItemsCount = layout.totalItemsCount,
            )
        }
            .distinctUntilChanged().collect {
                atLatest = it
                if (!navigating) viewModel.setAtLatest(isForeground && it)
            }
    }
    LaunchedEffect(viewModel, isForeground, atLatest) {
        viewModel.setAtLatest(isForeground && atLatest)
        if (!isForeground) viewModel.flushReadingPosition()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.flushReadingPosition() }
    }
    LaunchedEffect(viewModel, listState, isForeground) {
        if (!isForeground) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val ids = layout.visibleItemsInfo.mapNotNull { it.key as? String }.toSet()
            val anchor = layout.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }?.key as? String
            Triple(ids, anchor, listState.firstVisibleItemScrollOffset) to
                (userHasScrolled && !navigating && (state as? ChannelViewModel.UiState.Ready)?.history?.navigation == null)
        }.distinctUntilChanged().collect { (viewport, save) ->
            if (!navigating) viewModel.onViewport(viewport.first, viewport.second, viewport.third, save)
        }
    }
    LaunchedEffect(viewModel, history?.navigation?.sequence, isForeground) {
        if (!isForeground) return@LaunchedEffect
        val request = history?.navigation ?: return@LaunchedEffect
        val ready = state as? ChannelViewModel.UiState.Ready ?: return@LaunchedEffect
        var index = ready.messages.indexOfFirst { it.id == request.messageId }
        var offset = request.offset
        if (index < 0) {
            // ミュート等で移動先が非表示の場合は、表示可能な近傍へ移動する。
            val timestamp = ready.history.messages.firstOrNull { it.id == request.messageId }?.createdAt
            index = ready.messages.indices.minByOrNull {
                kotlin.math.abs(ready.messages[it].createdAt - (timestamp ?: 0))
            } ?: -1
            offset = 0
        }
        navigating = true
        try {
            if (index >= 0) {
                val gapIndex = ready.history.gapIndex(ready.messages)
                val listIndex = index + if (gapIndex != null && index >= gapIndex) 1 else 0
                if (request.animated) {
                    listState.animateScrollToItem(listIndex, offset)
                } else {
                    listState.scrollToItem(listIndex, offset)
                }
                withFrameNanos { }
                highlightedId = ready.messages[index].id
                atLatest = index == 0 && offset < 48
                viewModel.setAtLatest(isForeground && atLatest)
            }
            viewModel.consumeNavigation(request.sequence)
        } finally { navigating = false }
    }
    LaunchedEffect(highlightedId) {
        if (highlightedId != null) {
            delay(1_800)
            highlightedId = null
        }
    }
    LaunchedEffect(history?.notice) {
        history?.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeHistoryNotice()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = {
                    val title = (state as? ChannelViewModel.UiState.Ready)
                        ?.channelMeta?.name?.ifBlank { "チャンネル" } ?: "チャンネル"
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    val ready = state as? ChannelViewModel.UiState.Ready
                    if (ready != null) {
                        IconButton(onClick = { showThreadInfoDialog = true }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "スレッド情報",
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (history != null) {
                val movingToLatest = history.navigationTarget == ChannelNavigationTarget.Latest
                if (movingToLatest || !atLatest || history.newMessageCount > 0) {
                    ExtendedFloatingActionButton(onClick = {
                        if (!movingToLatest) {
                            userHasScrolled = true
                            viewModel.jumpToLatest()
                        }
                    }) {
                        if (movingToLatest) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("最新へ移動中")
                        } else {
                            Text(if (history.newMessageCount > 0) "新着あり・最新へ" else "最新へ")
                        }
                    }
                } else if (!history.isLoading && history.navigationTarget == null && history.hasPreviousPosition) {
                    ExtendedFloatingActionButton(onClick = {
                        userHasScrolled = true
                        viewModel.jumpToPrevious()
                    }) { Text("前回の続きへ") }
                }
            }
        },
        bottomBar = {
            val ready = state as? ChannelViewModel.UiState.Ready
            ChannelMessageInputBar(
                ready = ready,
                onDraftChange = viewModel::onDraftChange,
                onSend = viewModel::sendMessage,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (history?.isLoading == true) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("投稿を読み込み中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                history?.error?.let { error ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::retryMessages, enabled = !history.isLoading) { Text("再試行") }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val s = state) {
                        is ChannelViewModel.UiState.Loading -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = "メッセージを読み込み中…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        is ChannelViewModel.UiState.Ready -> {
                            if (s.messages.isEmpty()) {
                                Text(
                                    text = when {
                                        s.history.isLoading -> "最新の投稿を確認しています…"
                                        s.history.error != null -> "投稿を取得できませんでした"
                                        else -> "メッセージがありません"
                                    },
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 32.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    LazyColumn(
                                        state = listState,
                                        reverseLayout = true,
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        val gapIndex = s.history.gapIndex(s.messages)
                                        s.messages.forEachIndexed { index, message ->
                                            if (gapIndex == index) {
                                                item(key = "history-gap") {
                                                    ChannelHistoryGapButton(s.history.isLoading, viewModel::loadHistoryGap)
                                                }
                                            }
                                            item(key = message.id) {
                                                Box(Modifier.background(
                                                    if (message.id == highlightedId) MaterialTheme.colorScheme.secondaryContainer
                                                    else MaterialTheme.colorScheme.surface,
                                                )) {
                                                    NoteCard(
                                                        event = message,
                                                        profile = s.profiles[message.pubkey],
                                                        profiles = s.profiles,
                                                        replyCount = s.replyCounts[message.id] ?: 0,
                                                        reactionCount = s.reactionCounts[message.id] ?: 0,
                                                        likeReactionCount = s.likeReactionCounts[message.id] ?: 0,
                                                        customReactions = s.customReactions[message.id].orEmpty(),
                                                        unicodeReactions = s.unicodeReactions[message.id].orEmpty(),
                                                        reactionEvents = s.reactionEvents[message.id].orEmpty(),
                                                        repostCount = s.repostCounts[message.id] ?: 0,
                                                        repostPubkeys = s.repostPubkeys[message.id].orEmpty(),
                                                        isLiked = s.isLiked(message.id),
                                                        ownEmojiReactionEventIds = s.displayOwnEmojiReactionEventIds(message.id),
                                                        isReposted = s.isReposted(message.id),
                                                        onUserClick = onUserClick,
                                                        onLike = if (ownPubkey != null) {
                                                            {
                                                                if (s.isLiked(message.id)) {
                                                                    viewModel.unreact(message.id)
                                                                } else {
                                                                    viewModel.react(message.id, message.pubkey)
                                                                }
                                                            }
                                                        } else null,
                                                        onEmojiReact = if (ownPubkey != null) {
                                                            { option -> viewModel.reactWithEmoji(message.id, message.pubkey, option) }
                                                        } else null,
                                                        onEmojiUnreact = if (ownPubkey != null) {
                                                            { option -> viewModel.unreactWithEmoji(message.id, option) }
                                                        } else null,
                                                        onReply = if (ownPubkey != null && onReply != null) {
                                                            { onReply(message.id, message.pubkey, message.content.replyPreviewText(), channelId) }
                                                        } else null,
                                                        onOpenReplies = { onOpenThread(message.id) },
                                                        onOpenLikes = { onOpenLikes(message.id) },
                                                        onOpenReposts = { onOpenReposts(message.id) },
                                                        onRepost = if (ownPubkey != null) {
                                                            {
                                                                if (s.isReposted(message.id)) {
                                                                    viewModel.unrepost(message.id)
                                                                } else {
                                                                    viewModel.repost(message)
                                                                }
                                                            }
                                                        } else null,
                                                        ownPubkey = ownPubkey,
                                                        isMuted = mutedPubkeys.contains(message.pubkey),
                                                        onNoteClick = onOpenThread,
                                                    )
                                                }
                                                HorizontalDivider()
                                            }
                                        }
                                        if (gapIndex == s.messages.size) {
                                            item(key = "history-gap") {
                                                ChannelHistoryGapButton(s.history.isLoading, viewModel::loadHistoryGap)
                                            }
                                        }
                                        // reverseLayout により、末尾アイテムは画面上部に表示される
                                        if (s.canLoadMore) {
                                            item(key = "load-more-older") {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    FilledTonalButton(onClick = viewModel::loadMore, enabled = !s.history.isLoading) {
                                                        Text("さらに読み込む")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    LazyListScrollbar(
                                        state = listState,
                                        reverseLayout = true,
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxHeight()
                                            .padding(vertical = 8.dp, horizontal = 2.dp)
                                            .width(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val readyState = state as? ChannelViewModel.UiState.Ready
    if (showThreadInfoDialog && readyState != null) {
        ThreadInfoDialog(
            meta = readyState.channelMeta,
            ownerPubkey = readyState.channelOwnerPubkey,
            ownerProfile = readyState.channelOwnerPubkey?.let { readyState.profiles[it] },
            canEdit = ownPubkey != null && ownPubkey == readyState.channelOwnerPubkey,
            onDismiss = { showThreadInfoDialog = false },
            onEditClick = {
                showThreadInfoDialog = false
                viewModel.showEditThreadDialog()
            },
            onUserClick = onUserClick,
        )
    }

    val editDialog = (state as? ChannelViewModel.UiState.Ready)?.editDialog
    if (editDialog != null) {
        EditThreadDialog(
            state = editDialog,
            onTitleChange = viewModel::onEditTitleChange,
            onDescriptionChange = viewModel::onEditDescriptionChange,
            onSave = viewModel::saveThreadMeta,
            onDismiss = viewModel::dismissEditThreadDialog,
        )
    }
}

@Composable
private fun EditThreadDialog(
    state: ChannelViewModel.EditThreadDialogState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var titleValue by rememberSyncedTextFieldValue(state.title)
    var descriptionValue by rememberSyncedTextFieldValue(state.description)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("スレッドを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = titleValue,
                    onValueChange = {
                        titleValue = it
                        onTitleChange(it.text)
                    },
                    label = { Text("スレッドタイトル") },
                    singleLine = true,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = descriptionValue,
                    onValueChange = {
                        descriptionValue = it
                        onDescriptionChange(it.text)
                    },
                    label = { Text("スレッド説明") },
                    maxLines = 4,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = titleValue.text.isNotBlank() && !state.isSaving,
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isSaving,
            ) {
                Text("キャンセル")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelDetailRelayPendingContent(
    isLoaded: Boolean,
    hasEnabledRelays: Boolean,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = { Text("チャンネル") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !isLoaded -> CircularProgressIndicator()
                !hasEnabledRelays -> Text(
                    text = "有効なリレーがありません",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> CircularProgressIndicator()
            }
        }
    }
}


@Composable
private fun ChannelMessageInputBar(
    ready: ChannelViewModel.UiState.Ready?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    AppMessageComposer(
        text = ready?.draftText.orEmpty(),
        onTextChange = onDraftChange,
        onSend = onSend,
        placeholder = "メッセージを入力…",
        enabled = ready != null,
        isSending = ready?.isPosting == true,
        error = ready?.postError,
    )
}

@Composable
private fun ThreadInfoDialog(
    meta: ChannelMeta,
    ownerPubkey: String?,
    ownerProfile: com.nostr.torinos.model.NostrProfile?,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit,
    onUserClick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("スレッド情報") },
        text = {
            ThreadInfoContent(
                meta = meta,
                ownerPubkey = ownerPubkey,
                ownerProfile = ownerProfile,
                onUserClick = onUserClick,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
        dismissButton = if (canEdit) {
            {
                TextButton(onClick = onEditClick) { Text("編集") }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ThreadInfoContent(
    meta: ChannelMeta,
    ownerPubkey: String?,
    ownerProfile: com.nostr.torinos.model.NostrProfile?,
    onUserClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = meta.name.ifBlank { "（タイトルなし）" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.about.isNotBlank()) {
            LinkedText(
                text = meta.about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onProfileClick = onUserClick,
            )
        }
        if (ownerPubkey != null) {
            Spacer(modifier = Modifier.size(4.dp))
            Row(
                modifier = Modifier.clickable { onUserClick(ownerPubkey) },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarCircle(
                    pubkey = ownerPubkey,
                    name = ownerProfile?.bestName,
                    pictureUrl = ownerProfile?.picture,
                    size = 32,
                )
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = "スレッド作成者",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ProfileNameText(
                        profile = ownerProfile,
                        fallback = ownerPubkey.take(8) + "…" + ownerPubkey.takeLast(8),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun String.replyPreviewText(): String =
    stripImageUrls(stripNostrEventUris(this))
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(160)

@Composable
private fun ChannelHistoryGapButton(loading: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("この間に未取得の投稿があります", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onClick, enabled = !loading) { Text("この間を読み込む") }
    }
}

internal fun isChannelLatestVisible(
    newestMessageId: String?,
    visibleKeys: List<Any>,
    totalItemsCount: Int,
): Boolean = newestMessageId == null || totalItemsCount == 0 || newestMessageId in visibleKeys
