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
import com.nostr.torinos.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.components.NoteCard
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.profile.AvatarCircle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialQuery: String = "",
    onBack: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onOpenThread: (eventId: String) -> Unit = {},
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    viewModel: SearchViewModel = viewModel(key = "search") { SearchViewModel() },
) {
    var inputText by remember(initialQuery) { mutableStateOf(initialQuery) }
    val state by viewModel.state.collectAsState()
    val selectedTab = state.selectedTab
    val uriHandler = LocalUriHandler.current
    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground

    // hide()だけではフォーカスが残り、iOSでは画面を離れた後もテキスト入力セッションが続いて
    // 戻った先のスクロールが重くなる。フォーカスごと外す。
    val dismissKeyboard = rememberDismissKeyboard()

    fun doSearch() {
        dismissKeyboard()
        spotifySearchUriOrNull(inputText)?.let { uri ->
            uriHandler.openUri(uri)
            return
        }
        viewModel.search(inputText, selectedTab)
    }

    LaunchedEffect(initialQuery) {
        val spotifySearchUri = spotifySearchUriOrNull(initialQuery)
        if (spotifySearchUri != null) {
            uriHandler.openUri(spotifySearchUri)
        } else if (initialQuery.isNotBlank()) {
            viewModel.search(initialQuery, selectedTab)
        }
    }

    LifecycleStartEffect(viewModel) {
        viewModel.resume()
        onStopOrDispose { viewModel.stop() }
    }
    DismissKeyboardOnLeave()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
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
                selectedTabIndex = if (selectedTab == SearchTab.Posts) 0 else 1,
                containerColor = headerBackgroundColor,
                contentColor = headerContentColor,
            ) {
                Tab(
                    selected = selectedTab == SearchTab.Posts,
                    onClick = { viewModel.selectTab(SearchTab.Posts) },
                    text = { Text("ポスト") },
                )
                Tab(
                    selected = selectedTab == SearchTab.Users,
                    onClick = { viewModel.selectTab(SearchTab.Users) },
                    text = { Text("ユーザー") },
                )
            }

            // 結果エリア
            Box(modifier = Modifier.fillMaxSize()) {
                val loadState = when (selectedTab) {
                    SearchTab.Posts -> state.postsLoadState
                    SearchTab.Users -> state.usersLoadState
                }
                when {
                    state.query.isBlank() -> {
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

                    loadState == SearchLoadState.Loading || loadState == SearchLoadState.Interrupted -> {
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

                    loadState == SearchLoadState.Failed -> {
                        Text(
                            text = "検索に失敗しました。もう一度検索してください",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 32.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    loadState == SearchLoadState.NotRequested -> {
                        Text(
                            text = "検索ボタンを押して再検索してください",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 32.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    selectedTab == SearchTab.Users -> {
                        if (state.users.isEmpty()) {
                            Text(
                                text = "「${state.query}」に一致するユーザーはいません",
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 32.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(state.users, key = { it.first }) { (pubkey, profile) ->
                                    UserSearchRow(
                                        pubkey = pubkey,
                                        profile = profile,
                                        onClick = { onUserClick(pubkey) },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }

                    else -> {
                        if (state.events.isEmpty()) {
                            Text(
                                text = "「${state.query}」の結果はありませんでした",
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 32.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(state.events, key = { it.id }) { event ->
                                    NoteCard(
                                        event = event,
                                        profile = state.profiles[event.pubkey],
                                        profiles = state.profiles,
                                        replyCount = state.replyCounts[event.id] ?: 0,
                                        reactionCount = state.reactionCounts[event.id] ?: 0,
                                        likeReactionCount = state.likeReactionCounts[event.id] ?: 0,
                                        customReactions = state.customReactions[event.id].orEmpty(),
                                        unicodeReactions = state.unicodeReactions[event.id].orEmpty(),
                                        reactionEvents = state.reactionEvents[event.id].orEmpty(),
                                        repostCount = state.repostCounts[event.id] ?: 0,
                                        repostPubkeys = state.repostPubkeys[event.id].orEmpty(),
                                        onUserClick = onUserClick,
                                        onNoteClick = onOpenThread,
                                        onOpenReplies = { onOpenReplies(event.id) },
                                        onOpenLikes = { onOpenLikes(event.id) },
                                        onOpenReposts = { onOpenReposts(event.id) },
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
    }
}

internal fun spotifySearchUriOrNull(input: String): String? {
    val trimmed = input.trim()
    val prefix = "spotify:search:"
    if (!trimmed.startsWith(prefix, ignoreCase = true)) return null

    val query = trimmed.substring(prefix.length).trim()
    if (query.isBlank()) return null

    return prefix + query.encodeSpotifySearchQuery()
}

private fun String.encodeSpotifySearchQuery(): String {
    val builder = StringBuilder()
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.isUnreservedUriChar() -> builder.append(char)
            char == '%' && hasPercentEncodedByteAt(index) -> {
                builder.append('%')
                builder.append(this[index + 1].uppercaseChar())
                builder.append(this[index + 2].uppercaseChar())
                index += 2
            }
            else -> char.toString().encodeToByteArray().forEach { byte ->
                builder.append('%')
                builder.append(byte.toUByte().toString(16).uppercase().padStart(2, '0'))
            }
        }
        index++
    }
    return builder.toString()
}

private fun Char.isUnreservedUriChar(): Boolean =
    this in 'A'..'Z' ||
        this in 'a'..'z' ||
        this in '0'..'9' ||
        this == '-' ||
        this == '.' ||
        this == '_' ||
        this == '~'

private fun String.hasPercentEncodedByteAt(index: Int): Boolean =
    index + 2 < length && this[index + 1].isHexDigit() && this[index + 2].isHexDigit()

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

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
            ProfileNameText(
                profile = profile,
                fallback = pubkey.take(8) + "…" + pubkey.takeLast(8),
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
