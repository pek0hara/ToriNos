package com.nostr.torinos.ui.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NoteCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    channelId: String,
    onBack: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onOpenThread: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    ownPubkey: String? = null,
) {
    val selectedRelayUrl by RelayStore.selectedRelayUrl.collectAsState()
    val viewModel: ChannelViewModel = viewModel(key = "$channelId-${selectedRelayUrl ?: "all"}") {
        ChannelViewModel(channelId = channelId, relayUrl = selectedRelayUrl)
    }
    val state by viewModel.state.collectAsState()
    val mutedPubkeys by MuteStore.mutedPubkeys.collectAsState()
    val listState = rememberSaveable(channelId, selectedRelayUrl, saver = LazyListState.Saver) { LazyListState() }
    var didApplyInitialScroll by remember(channelId, selectedRelayUrl) { mutableStateOf(false) }

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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LaunchedEffect(listState) {
                                snapshotFlow { !listState.canScrollForward && listState.layoutInfo.totalItemsCount > 0 }
                                    .distinctUntilChanged()
                                    .filter { it }
                                    .collect { viewModel.onScrolledToLatest() }
                            }

                            LaunchedEffect(channelId, selectedRelayUrl, s.initialUnreadMessageId, s.messages.size) {
                                val isAtTop = listState.firstVisibleItemIndex == 0 &&
                                    listState.firstVisibleItemScrollOffset == 0
                                if (!didApplyInitialScroll) {
                                    val targetIndex = s.initialUnreadMessageId
                                        ?.let { id -> s.messages.indexOfFirst { it.id == id } }
                                        ?.takeIf { it >= 0 }
                                        ?: 0
                                    listState.scrollToItem(targetIndex)
                                    didApplyInitialScroll = true
                                } else if (s.keepScrolledToTop && isAtTop) {
                                    listState.scrollToItem(0)
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(s.messages, key = { it.id }) { message ->
                                    NoteCard(
                                        event = message,
                                        profile = s.profiles[message.pubkey],
                                        profiles = s.profiles,
                                        replyCount = 0,
                                        reactionCount = 0,
                                        onUserClick = onUserClick,
                                        onOpenReplies = { onOpenThread(message.id) },
                                        onOpenLikes = { onOpenLikes(message.id) },
                                        onOpenReposts = { onOpenReposts(message.id) },
                                        ownPubkey = ownPubkey,
                                        isMuted = mutedPubkeys.contains(message.pubkey),
                                    )
                                    HorizontalDivider()
                                }
                                if (s.canLoadMore) {
                                    item {
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
                        }
                    }
                }
            }

            // 入力バー
            val ready = state as? ChannelViewModel.UiState.Ready
            HorizontalDivider()
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = ready?.draftText ?: "",
                        onValueChange = viewModel::onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("メッセージを入力…") },
                        maxLines = 4,
                        enabled = ready != null && !ready.isPosting,
                    )
                    IconButton(
                        onClick = viewModel::sendMessage,
                        enabled = ready != null && ready.draftText.isNotBlank() && !ready.isPosting,
                    ) {
                        if (ready?.isPosting == true) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "送信",
                                tint = if (ready != null && ready.draftText.isNotBlank())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
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
    }
}
