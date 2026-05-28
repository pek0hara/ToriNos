package com.nostr.torinos.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.components.NoteCard
import com.nostr.torinos.ui.profile.AvatarCircle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialQuery: String = "",
    onBack: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    viewModel: SearchViewModel = viewModel(key = "search") { SearchViewModel() },
) {
    var inputText by remember(initialQuery) { mutableStateOf(initialQuery) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val state by viewModel.state.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground

    fun doSearch() {
        keyboardController?.hide()
        viewModel.search(inputText)
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            viewModel.search(initialQuery)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("検索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                            tint = headerContentColor,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = headerBackgroundColor,
                    scrolledContainerColor = headerBackgroundColor,
                    titleContentColor = headerContentColor,
                    actionIconContentColor = headerContentColor,
                    navigationIconContentColor = headerContentColor,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 検索入力エリア
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("キーワードまたは #タグ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                )
                IconButton(onClick = { doSearch() }) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "検索",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // タブ
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = headerBackgroundColor,
                contentColor = headerContentColor,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("ポスト") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("ユーザー") },
                )
            }

            // 結果エリア
            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    is SearchViewModel.UiState.Idle -> {
                        Text(
                            text = "キーワードまたは #タグ を入力して検索",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 32.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    is SearchViewModel.UiState.Loading -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "検索中…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is SearchViewModel.UiState.Ready -> {
                        if (selectedTab == 1) {
                            if (s.users.isEmpty()) {
                                Text(
                                    text = "「${s.query}」に一致するユーザーはいません",
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 32.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(s.users, key = { it.first }) { (pubkey, profile) ->
                                        UserSearchRow(
                                            pubkey = pubkey,
                                            profile = profile,
                                            onClick = { onUserClick(pubkey) },
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                        } else {
                            if (s.events.isEmpty()) {
                                Text(
                                    text = "「${s.query}」の結果はありませんでした",
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 32.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(s.events, key = { it.id }) { event ->
                                        NoteCard(
                                            event = event,
                                            profile = s.profiles[event.pubkey],
                                            profiles = s.profiles,
                                            replyCount = s.replyCounts[event.id] ?: 0,
                                            reactionCount = s.reactionCounts[event.id] ?: 0,
                                            repostCount = s.repostCounts[event.id] ?: 0,
                                            onUserClick = onUserClick,
                                            onOpenReplies = { onOpenReplies(event.id) },
                                            onOpenLikes = { onOpenLikes(event.id) },
                                            onOpenReposts = { onOpenReposts(event.id) },
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
            }
        }
    }
}

@Composable
private fun UserSearchRow(pubkey: String, profile: NostrProfile, onClick: () -> Unit) {
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
            name = profile.bestName,
            pictureUrl = profile.picture,
            size = 44,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = profile.bestName ?: (pubkey.take(8) + "…" + pubkey.takeLast(8)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            profile.nip05?.takeIf { it.isNotBlank() }?.let { nip05 ->
                Text(
                    text = nip05,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } ?: profile.about?.takeIf { it.isNotBlank() }?.let { about ->
                Text(
                    text = about,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
