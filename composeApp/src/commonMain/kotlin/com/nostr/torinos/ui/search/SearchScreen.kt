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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.article.LocalMatometrSelectionEnabled
import com.nostr.torinos.ui.article.MatometrSelectionBar
import com.nostr.torinos.ui.article.MatometrStartButton
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus

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
    val state by viewModel.state.collectAsState()
    val posts by viewModel.posts.collectAsState()
    val uriHandler = LocalUriHandler.current

    // 遷移先から戻るとこの画面は作り直されるが、ViewModelは残る。初期クエリは同じ値で1回だけ適用する。
    LaunchedEffect(initialQuery) {
        if (!viewModel.markInitialQueryApplied(initialQuery)) return@LaunchedEffect
        val spotifySearchUri = spotifySearchUriOrNull(initialQuery)
        if (spotifySearchUri != null) {
            uriHandler.openUri(spotifySearchUri)
        } else if (initialQuery.isNotBlank()) {
            viewModel.search(initialQuery, viewModel.state.value.selectedTab)
        }
    }

    LifecycleStartEffect(viewModel) {
        viewModel.resume()
        onStopOrDispose { viewModel.stop() }
    }
    DismissKeyboardOnLeave()

    val actions = remember(viewModel, onBack, onUserClick, onOpenThread, onOpenReplies, onOpenLikes, onOpenReposts) {
        SearchActions(
            onBack = onBack,
            onSearch = { query -> viewModel.search(query, viewModel.state.value.selectedTab) },
            onSelectTab = viewModel::selectTab,
            onLoadMore = viewModel::loadMore,
            onRetryPosts = viewModel::retryPosts,
            onRetryUsers = viewModel::retryUsers,
            onUserClick = onUserClick,
            onOpenThread = onOpenThread,
            onOpenReplies = onOpenReplies,
            onOpenLikes = onOpenLikes,
            onOpenReposts = onOpenReposts,
        )
    }
    SearchContent(initialQuery = initialQuery, state = state, posts = posts, actions = actions)
}

internal class SearchActions(
    val onBack: () -> Unit,
    val onSearch: (query: String) -> Unit,
    val onSelectTab: (SearchTab) -> Unit,
    val onLoadMore: () -> Unit,
    val onRetryPosts: () -> Unit,
    val onRetryUsers: () -> Unit,
    val onUserClick: (pubkey: String) -> Unit,
    val onOpenThread: (eventId: String) -> Unit,
    val onOpenReplies: (eventId: String) -> Unit,
    val onOpenLikes: (eventId: String) -> Unit,
    val onOpenReposts: (eventId: String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchContent(
    initialQuery: String,
    state: SearchUiState,
    posts: PostSearchUiState,
    actions: SearchActions,
) {
    // 遷移先から戻ったときも、入力中の文字列を保つ。
    var inputText by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    val selectedTab = state.selectedTab
    val uriHandler = LocalUriHandler.current
    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground
    // タブを往復しても位置を保つため、タブごとに一覧の状態を持つ。
    val postsListState = rememberLazyListState()
    val usersListState = rememberLazyListState()
    var listedSearchId by rememberSaveable { mutableIntStateOf(posts.searchId) }

    LaunchedEffect(posts.searchId) {
        if (posts.searchId != listedSearchId) {
            listedSearchId = posts.searchId
            postsListState.scrollToItem(0)
            usersListState.scrollToItem(0)
        }
    }

    // hide()だけではフォーカスが残り、iOSでは画面を離れた後もテキスト入力セッションが続いて
    // 戻った先のスクロールが重くなる。フォーカスごと外す。
    val dismissKeyboard = rememberDismissKeyboard()

    fun doSearch() {
        dismissKeyboard()
        spotifySearchUriOrNull(inputText)?.let { uri ->
            uriHandler.openUri(uri)
            return
        }
        actions.onSearch(inputText)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                AppTopBar(
                    title = { Text("検索") },
                    navigationIcon = {
                        IconButton(onClick = actions.onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "戻る",
                                tint = headerContentColor,
                            )
                        }
                    },
                    actions = { MatometrStartButton() },
                )
                MatometrSelectionBar()
            }
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
                    modifier = Modifier.weight(1f).avoidStaleAccessibilityFocus(),
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
                    onClick = { actions.onSelectTab(SearchTab.Posts) },
                    text = { Text("ポスト") },
                )
                Tab(
                    selected = selectedTab == SearchTab.Users,
                    onClick = { actions.onSelectTab(SearchTab.Users) },
                    text = { Text("ユーザー") },
                )
            }

            // 結果エリア
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.query.isBlank() -> SearchMessage(
                        text = "キーワードまたは #タグ を入力して検索",
                        modifier = Modifier.align(Alignment.Center),
                    )
                    selectedTab == SearchTab.Posts -> CompositionLocalProvider(LocalMatometrSelectionEnabled provides true) {
                        PostSearchResults(
                            query = state.query,
                            state = posts,
                            listState = postsListState,
                            onLoadMore = actions.onLoadMore,
                            onRetry = actions.onRetryPosts,
                            onUserClick = actions.onUserClick,
                            onOpenThread = actions.onOpenThread,
                            onOpenReplies = actions.onOpenReplies,
                            onOpenLikes = actions.onOpenLikes,
                            onOpenReposts = actions.onOpenReposts,
                        )
                    }
                    else -> UserSearchResults(
                        state = state,
                        listState = usersListState,
                        onRetry = actions.onRetryUsers,
                        onUserClick = actions.onUserClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserSearchResults(
    state: SearchUiState,
    listState: LazyListState,
    onRetry: () -> Unit,
    onUserClick: (pubkey: String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state.usersLoadState) {
            SearchLoadState.Loading, SearchLoadState.Interrupted ->
                SearchLoadingIndicator(Modifier.align(Alignment.Center))
            SearchLoadState.Failed -> SearchFailure(
                message = "ユーザー検索に失敗しました",
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
            SearchLoadState.NotRequested -> SearchMessage(
                text = "検索ボタンを押して再検索してください",
                modifier = Modifier.align(Alignment.Center),
            )
            SearchLoadState.Ready -> if (state.users.isEmpty()) {
                SearchMessage(
                    text = "「${state.query}」に一致するユーザーはいません",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
