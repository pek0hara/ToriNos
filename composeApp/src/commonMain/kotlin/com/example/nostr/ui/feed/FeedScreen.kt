package com.example.nostr.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nostr.ui.components.NoteCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    /** null = グローバルフィード、非null = 特定ユーザーの投稿 */
    authorPubkey: String? = null,
    viewModel: FeedViewModel = viewModel(key = authorPubkey ?: "global") {
        FeedViewModel(authorPubkey)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        viewModel.startSubscriptions()
        onDispose {
            viewModel.stopSubscriptions()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (authorPubkey != null) "投稿" else "ToraNosu") },
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
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.isInitialLoad) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "リレーに接続中…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                if (state.events.isEmpty()) {
                    Text(
                        text = "投稿がありません",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.events, key = { it.id }) { event ->
                            NoteCard(
                                event = event,
                                profile = state.profiles[event.pubkey],
                                replyCount = state.replyCounts[event.id] ?: 0,
                                reactionCount = state.reactionCounts[event.id] ?: 0,
                                onUserClick = onUserClick,
                            )
                            HorizontalDivider()
                        }
                        if (state.canLoadMore) {
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
}
