package com.nostr.torinos.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.ui.components.NoteCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    eventId: String,
    onBack: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String) -> Unit)? = null,
    onOpenThread: (eventId: String) -> Unit = {},
    ownPubkey: String? = null,
    viewModel: ThreadViewModel = viewModel(key = "thread-$eventId") { ThreadViewModel(eventId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        viewModel.startSubscriptions()
        onDispose { viewModel.stopSubscriptions() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("スレッド") },
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
                        text = "投稿を読み込めませんでした",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            val root = state.root ?: return@item
                            NoteCard(
                                event = root,
                                profile = state.profiles[root.pubkey],
                                replyCount = state.replyCounts[root.id] ?: state.replies.size,
                                reactionCount = 0,
                                onUserClick = onUserClick,
                                onReply = if (ownPubkey != null && onReply != null) {
                                    { onReply(root.id, root.pubkey) }
                                } else null,
                            )
                            HorizontalDivider()
                        }
                        item {
                            Text(
                                text = "返信",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider()
                        }
                        if (state.replies.isEmpty()) {
                            item {
                                Text(
                                    text = "返信はまだありません",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            items(state.replies, key = { it.id }) { reply ->
                                val replyCount = state.replyCounts[reply.id] ?: 0
                                NoteCard(
                                    event = reply,
                                    profile = state.profiles[reply.pubkey],
                                    replyCount = replyCount,
                                    reactionCount = 0,
                                    onUserClick = onUserClick,
                                    onReply = if (ownPubkey != null && onReply != null) {
                                        { onReply(reply.id, reply.pubkey) }
                                    } else null,
                                    onOpenReplies = if (replyCount > 0) {
                                        { onOpenThread(reply.id) }
                                    } else null,
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
