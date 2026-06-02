package com.nostr.torinos.ui.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LazyListScrollbar
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.NoteCard
import com.nostr.torinos.ui.components.ProfileNameText
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
    val selectedRelayUrl by RelayStore.selectedChannelRelayUrl.collectAsState()
    val viewModel: ChannelViewModel = viewModel(key = "$channelId-${selectedRelayUrl ?: "all"}") {
        ChannelViewModel(channelId = channelId, relayUrl = selectedRelayUrl)
    }
    val state by viewModel.state.collectAsState()
    val mutedPubkeys by MuteStore.mutedPubkeys.collectAsState()
    val listState = remember(channelId, selectedRelayUrl) { LazyListState() }
    var showThreadInfoDialog by remember(channelId, selectedRelayUrl) { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
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
                            tint = MaterialTheme.colorScheme.onPrimary,
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
                            text = "メッセージがありません",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 32.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // 最新メッセージが届いた時、最下部付近にいれば自動スクロール
                        val newestMessageId = s.messages.firstOrNull()?.id
                        LaunchedEffect(newestMessageId) {
                            if (newestMessageId != null && listState.firstVisibleItemIndex <= 1) {
                                listState.scrollToItem(0)
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = listState,
                                reverseLayout = true,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(s.messages, key = { it.id }) { message ->
                                    NoteCard(
                                        event = message,
                                        profile = s.profiles[message.pubkey],
                                        profiles = s.profiles,
                                        replyCount = s.replyCounts[message.id] ?: 0,
                                        reactionCount = s.reactionCounts[message.id] ?: 0,
                                        repostCount = s.repostCounts[message.id] ?: 0,
                                        isLiked = s.likedReactions.containsKey(message.id),
                                        isReposted = s.repostedEvents.containsKey(message.id),
                                        onUserClick = onUserClick,
                                        onLike = if (ownPubkey != null) {
                                            {
                                                if (s.likedReactions.containsKey(message.id)) {
                                                    viewModel.unreact(message.id)
                                                } else {
                                                    viewModel.react(message.id, message.pubkey)
                                                }
                                            }
                                        } else null,
                                        onReply = if (ownPubkey != null && onReply != null) {
                                            { onReply(message.id, message.pubkey, message.content.replyPreviewText(), channelId) }
                                        } else null,
                                        onOpenReplies = { onOpenThread(message.id) },
                                        onOpenLikes = { onOpenLikes(message.id) },
                                        onOpenReposts = { onOpenReposts(message.id) },
                                        onRepost = if (ownPubkey != null) {
                                            {
                                                if (s.repostedEvents.containsKey(message.id)) {
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
                                    HorizontalDivider()
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
                                            FilledTonalButton(onClick = viewModel::loadMore) {
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
        AlertDialog(
            onDismissRequest = viewModel::dismissEditThreadDialog,
            title = { Text("スレッドを編集") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editDialog.title,
                        onValueChange = viewModel::onEditTitleChange,
                        label = { Text("スレッドタイトル") },
                        singleLine = true,
                        enabled = !editDialog.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editDialog.description,
                        onValueChange = viewModel::onEditDescriptionChange,
                        label = { Text("スレッド説明") },
                        maxLines = 4,
                        enabled = !editDialog.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    editDialog.error?.let {
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
                    onClick = viewModel::saveThreadMeta,
                    enabled = editDialog.title.isNotBlank() && !editDialog.isSaving,
                ) {
                    if (editDialog.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("保存")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissEditThreadDialog,
                    enabled = !editDialog.isSaving,
                ) {
                    Text("キャンセル")
                }
            },
        )
    }
}


@Composable
private fun ChannelMessageInputBar(
    ready: ChannelViewModel.UiState.Ready?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    HorizontalDivider()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = ready?.draftText ?: "",
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("メッセージを入力…") },
                maxLines = 4,
                enabled = ready != null && !ready.isPosting,
            )
            IconButton(
                onClick = onSend,
                enabled = ready != null && ready.draftText.isNotBlank() && !ready.isPosting,
            ) {
                if (ready?.isPosting == true) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "送信",
                        tint = if (ready != null && ready.draftText.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        if (ready?.postError != null) {
            Text(
                text = ready.postError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }
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
