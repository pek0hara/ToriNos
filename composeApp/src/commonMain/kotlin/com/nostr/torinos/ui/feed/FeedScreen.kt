package com.nostr.torinos.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.noteListItems
import com.nostr.torinos.ui.profile.AvatarCircle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String) -> Unit)? = null,
    ownPubkey: String? = null,
    ownProfile: NostrProfile? = null,
    /** null = グローバルフィード、非null = 特定ユーザーの投稿 */
    authorPubkey: String? = null,
) {
    val relays by RelayStore.relays.collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedRelayUrl by remember { mutableStateOf<String?>(null) }
    var showRelayMenu by remember { mutableStateOf(false) }

    // リレーリストが変わったら選択中 URL を有効なものに補正
    LaunchedEffect(relays) {
        if (selectedRelayUrl == null || selectedRelayUrl !in relays) {
            selectedRelayUrl = relays.firstOrNull()
        }
    }

    val viewModel: FeedViewModel = viewModel(
        key = "${authorPubkey ?: "global"}-${selectedRelayUrl ?: "none"}",
    ) {
        FeedViewModel(authorPubkey, selectedRelayUrl)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        viewModel.startSubscriptions()
        onDispose {
            viewModel.stopSubscriptions()
        }
    }

    val listState = rememberLazyListState()
    val reachedBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible != null &&
                lastVisible.index >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(reachedBottom, state.canLoadMore) {
        if (reachedBottom && state.canLoadMore) viewModel.loadMore()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    if (authorPubkey != null) {
                        Text("投稿")
                    } else {
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
                                            selectedRelayUrl = url
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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            noteListItems(
                state = state,
                ownPubkey = ownPubkey,
                onUserClick = onUserClick,
                onLike = viewModel::react,
                onUnlike = viewModel::unreact,
                onDelete = viewModel::deleteEvent,
                onReply = onReply,
                onRepost = { eventId, _ ->
                    state.events.find { it.id == eventId }?.let { viewModel.repost(it) }
                },
            )
        }
    }
}

private fun String.relayDisplayName(): String =
    removePrefix("wss://").removePrefix("ws://").trimEnd('/')
