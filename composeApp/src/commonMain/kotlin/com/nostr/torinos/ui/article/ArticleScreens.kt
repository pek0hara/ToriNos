package com.nostr.torinos.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.nostr.torinos.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.ArticleAuthorItem
import com.nostr.torinos.model.ArticleItem
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.extractImageUrls
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.stripImageUrls
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.profile.customEmojiMap
import com.nostr.torinos.ui.service.ServiceTab
import com.nostr.torinos.ui.service.ServiceTabRow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleHubScreen(
    ownPubkey: String?,
    ownProfile: NostrProfile?,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onArticleClick: (pubkey: String, identifier: String) -> Unit,
    onAuthorClick: (pubkey: String) -> Unit,
    selectedServiceTab: ServiceTab,
    onServiceTabSelected: (ServiceTab) -> Unit,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedArticleRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()
    var showRelayMenu by remember { mutableStateOf(false) }

    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        RelaySelectionPendingContent(
            isLoaded = isRelayStoreLoaded,
            hasEnabledRelays = relays.isNotEmpty(),
        )
        return
    }

    val viewModel: ArticleHubViewModel = viewModel(key = "article-hub-$activeRelayUrl") {
        ArticleHubViewModel(relayUrl = activeRelayUrl)
    }
    val state by viewModel.state.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(ArticleHubTab.Articles) }
    val followedPubkeys by FollowRepository.followedPubkeys.collectAsState()
    var isMyAuthorsExpanded by rememberSaveable { mutableStateOf(true) }
    var isFollowingAuthorsExpanded by rememberSaveable { mutableStateOf(true) }
    var isGlobalAuthorsExpanded by rememberSaveable { mutableStateOf(true) }
    val listState = rememberSaveable(activeRelayUrl, selectedTab, saver = LazyListState.Saver) { LazyListState() }
    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground

    LaunchedEffect(
        listState,
        selectedTab,
        isMyAuthorsExpanded,
        isFollowingAuthorsExpanded,
        isGlobalAuthorsExpanded,
        state.canLoadMore,
        state.isLoadingMore,
    ) {
        snapshotFlow {
            val canAutoLoadMore = selectedTab == ArticleHubTab.Articles ||
                isMyAuthorsExpanded ||
                isFollowingAuthorsExpanded ||
                isGlobalAuthorsExpanded
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            canAutoLoadMore &&
                lastVisible >= layoutInfo.totalItemsCount - 4 &&
                state.canLoadMore &&
                !state.isLoadingMore
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMore() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(modifier = Modifier.background(headerBackgroundColor)) {
                AppTopBar(
                    navigationIcon = {
                        if (ownPubkey != null) {
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
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Text(
                                text = selectedRelayUrl?.relayDisplayName() ?: "—",
                                modifier = Modifier.weight(1f, fill = false),
                                color = headerContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { showRelayMenu = true }) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "リレー切り替え",
                                    tint = headerContentColor,
                                )
                            }
                            DropdownMenu(
                                expanded = showRelayMenu,
                                onDismissRequest = { showRelayMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("リレー設定") },
                                    onClick = {
                                        showRelayMenu = false
                                        onOpenRelaySettings()
                                    },
                                )
                                relays.forEach { url ->
                                    DropdownMenuItem(
                                        text = { Text(url.relayDisplayName()) },
                                        onClick = {
                                            RelayStore.setSelectedArticleRelayUrl(url)
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
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "設定",
                                tint = headerContentColor,
                            )
                        }
                    },
                )
                ServiceTabRow(
                    selectedTab = selectedServiceTab,
                    onTabSelected = onServiceTabSelected,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            ArticleListContent(
                state = state,
                listState = listState,
                selectedTab = selectedTab,
                onArticleClick = onArticleClick,
                onAuthorClick = onAuthorClick,
                onLoadMore = viewModel::loadMore,
                ownPubkey = ownPubkey,
                followedPubkeys = followedPubkeys,
                isMyAuthorsExpanded = isMyAuthorsExpanded,
                isFollowingAuthorsExpanded = isFollowingAuthorsExpanded,
                isGlobalAuthorsExpanded = isGlobalAuthorsExpanded,
                onToggleMyAuthors = { isMyAuthorsExpanded = !isMyAuthorsExpanded },
                onToggleFollowingAuthors = { isFollowingAuthorsExpanded = !isFollowingAuthorsExpanded },
                onToggleGlobalAuthors = { isGlobalAuthorsExpanded = !isGlobalAuthorsExpanded },
                contentPadding = PaddingValues(top = 48.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .articleHubSwipe(
                        selectedArticleTab = selectedTab,
                        onArticleTabSelected = { selectedTab = it },
                        selectedServiceTab = selectedServiceTab,
                        onServiceTabSelected = onServiceTabSelected,
                    ),
            )
            ArticleHubSegmentedControl(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun ArticleHubSegmentedControl(
    selectedTab: ArticleHubTab,
    onTabSelected: (ArticleHubTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier
                .width(240.dp)
                .height(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 6.dp,
        ) {
            Row(modifier = Modifier.padding(3.dp)) {
                ArticleHubTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .clickable { onTabSelected(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelaySelectionPendingContent(
    isLoaded: Boolean,
    hasEnabledRelays: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !isLoaded -> CircularProgressIndicator()
            !hasEnabledRelays -> Text(
                text = "有効なリレーがありません",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserArticleListScreen(
    pubkey: String,
    onBack: () -> Unit,
    onArticleClick: (pubkey: String, identifier: String) -> Unit,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedArticleRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()
    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        RelaySelectionPendingContent(
            isLoaded = isRelayStoreLoaded,
            hasEnabledRelays = relays.isNotEmpty(),
        )
        return
    }

    val viewModel: UserArticleListViewModel = viewModel(key = "user-articles-$pubkey-$activeRelayUrl") {
        UserArticleListViewModel(pubkey, relayUrl = activeRelayUrl)
    }
    val state by viewModel.state.collectAsState()
    val listState = rememberSaveable(pubkey, selectedRelayUrl, saver = LazyListState.Saver) { LazyListState() }
    val profile = state.profiles[pubkey]

    LaunchedEffect(listState, state.canLoadMore, state.isLoadingMore) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= layoutInfo.totalItemsCount - 4 &&
                state.canLoadMore &&
                !state.isLoadingMore
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMore() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                title = {
                    Text(
                        text = "${profile?.bestName ?: pubkey.take(8)} の記事",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        ArticleListContent(
            state = state,
            listState = listState,
            selectedTab = ArticleHubTab.Articles,
            onArticleClick = onArticleClick,
            onAuthorClick = {},
            onLoadMore = viewModel::loadMore,
            emptyText = "記事がありません",
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    pubkey: String,
    identifier: String,
    ownPubkey: String?,
    onBack: () -> Unit,
    onEditArticle: (pubkey: String, identifier: String) -> Unit,
    onUserClick: (pubkey: String) -> Unit,
    onNoteClick: (eventId: String) -> Unit,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedArticleRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()
    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        RelaySelectionPendingContent(
            isLoaded = isRelayStoreLoaded,
            hasEnabledRelays = relays.isNotEmpty(),
        )
        return
    }

    val viewModel: ArticleDetailViewModel = viewModel(key = "article-$pubkey-$identifier-$activeRelayUrl") {
        ArticleDetailViewModel(pubkey, identifier, relayUrl = activeRelayUrl)
    }
    val state by viewModel.state.collectAsState()
    var showDeleteDialog by rememberSaveable(pubkey, identifier) { mutableStateOf(false) }

    LaunchedEffect(state.deleteCompletedCount) {
        if (state.deleteCompletedCount > 0) {
            showDeleteDialog = false
            onBack()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                title = {
                    Text(
                        text = state.article?.displayTitle ?: "記事",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    val article = state.article
                    if (article != null && ownPubkey != null && article.event.pubkey == ownPubkey) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            enabled = !state.isDeleting,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "記事を削除")
                        }
                        IconButton(
                            onClick = { onEditArticle(article.event.pubkey, article.meta.identifier) },
                            enabled = !state.isDeleting,
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "記事を編集")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.article != null -> state.article?.let { article ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(contentType = "article") {
                        ArticleDetailContent(
                            article = article,
                            quotedEvents = state.quotedEvents,
                            quotedProfiles = state.quotedProfiles,
                            loadingQuoteIds = state.loadingQuoteIds,
                            onUserClick = onUserClick,
                            onNoteClick = onNoteClick,
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        DeleteArticleDialog(
            isDeleting = state.isDeleting,
            error = state.deleteError,
            onDismiss = {
                if (!state.isDeleting) showDeleteDialog = false
            },
            onConfirm = viewModel::deleteArticle,
        )
    }
}

@Composable
private fun DeleteArticleDialog(
    isDeleting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("記事を削除") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("有効なリレーへ削除要求を送信します。対応していないリレーやキャッシュ済みデータからの削除は保証されません。")
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("削除する")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
            ) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun ArticleListContent(
    state: ArticleListState,
    listState: LazyListState,
    selectedTab: ArticleHubTab,
    onArticleClick: (pubkey: String, identifier: String) -> Unit,
    onAuthorClick: (pubkey: String) -> Unit,
    onLoadMore: () -> Unit,
    ownPubkey: String? = null,
    followedPubkeys: Set<String> = emptySet(),
    isMyAuthorsExpanded: Boolean = true,
    isFollowingAuthorsExpanded: Boolean = true,
    isGlobalAuthorsExpanded: Boolean = true,
    onToggleMyAuthors: () -> Unit = {},
    onToggleFollowingAuthors: () -> Unit = {},
    onToggleGlobalAuthors: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    emptyText: String = "記事がありません",
) {
    when {
        state.isInitialLoad -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.error != null -> Box(
            modifier = modifier.padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
            )
        }
        selectedTab == ArticleHubTab.Articles && state.articles.isEmpty() -> EmptyArticleList(modifier, emptyText)
        selectedTab == ArticleHubTab.Users && state.authors.isEmpty() && !state.canLoadMore -> {
            EmptyArticleList(modifier, "記事を書いているユーザーが見つかりません")
        }
        else -> LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = contentPadding,
        ) {
            when (selectedTab) {
                ArticleHubTab.Articles -> {
                    items(
                        items = state.articles,
                        key = { it.address },
                        contentType = { "article" },
                    ) { article ->
                        ArticleCard(
                            article = article,
                            onClick = { onArticleClick(article.event.pubkey, article.meta.identifier) },
                        )
                        HorizontalDivider()
                    }
                }
                ArticleHubTab.Users -> {
                    val myAuthors = state.authors.filter { it.pubkey == ownPubkey }
                    val followingAuthors = state.authors.filter {
                        it.pubkey != ownPubkey && it.pubkey in followedPubkeys
                    }
                    val globalAuthors = state.authors.filter {
                        it.pubkey != ownPubkey && it.pubkey !in followedPubkeys
                    }
                    articleAuthorSection(
                        title = "自分",
                        authors = myAuthors,
                        expanded = isMyAuthorsExpanded,
                        onToggle = onToggleMyAuthors,
                        onAuthorClick = onAuthorClick,
                    )
                    articleAuthorSection(
                        title = "フォロー",
                        authors = followingAuthors,
                        expanded = isFollowingAuthorsExpanded,
                        onToggle = onToggleFollowingAuthors,
                        onAuthorClick = onAuthorClick,
                    )
                    articleAuthorSection(
                        title = "グローバル",
                        authors = globalAuthors,
                        expanded = isGlobalAuthorsExpanded,
                        onToggle = onToggleGlobalAuthors,
                        onAuthorClick = onAuthorClick,
                    )
                }
            }
            if (state.isLoadingMore) {
                item(contentType = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (selectedTab == ArticleHubTab.Users && state.canLoadMore) {
                item(key = "user-load-more", contentType = "load-more") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Button(onClick = onLoadMore) {
                            Text("もっと読み込む")
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.articleAuthorSection(
    title: String,
    authors: List<ArticleAuthorItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAuthorClick: (pubkey: String) -> Unit,
) {
    item(key = "author-section-$title", contentType = "author-section-header") {
        ArticleAuthorSectionHeader(
            title = title,
            count = authors.size,
            expanded = expanded,
            onToggle = onToggle,
        )
        HorizontalDivider()
    }
    if (expanded) {
        if (authors.isEmpty()) {
            item(key = "author-section-$title-empty", contentType = "author-section-empty") {
                Text(
                    text = "該当するユーザーはいません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
                HorizontalDivider()
            }
        } else {
            items(
                items = authors,
                key = { "$title-${it.pubkey}" },
                contentType = { "author" },
            ) { author ->
                ArticleAuthorRow(
                    author = author,
                    onClick = { onAuthorClick(author.pubkey) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ArticleAuthorSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.rotate(if (expanded) 0f else -90f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyArticleList(modifier: Modifier, text: String) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArticleCard(
    article: ArticleItem,
    onClick: () -> Unit,
) {
    val imageUrl = article.meta.imageUrl?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        imageUrl?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                NetworkImage(
                    url = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    maxDecodeSizePx = 900,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.72f),
                                ),
                            ),
                        ),
                )
                Text(
                    text = article.displayTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
        if (imageUrl == null) {
            Text(
                text = article.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (article.displaySummary.isNotBlank()) {
            Text(
                text = article.displaySummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ArticleAuthorLine(article = article, onUserClick = onClick)
        if (article.meta.topics.isNotEmpty()) {
            Text(
                text = article.meta.topics.take(5).joinToString("  ") { "#$it" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArticleAuthorLine(
    article: ArticleItem,
    onUserClick: () -> Unit,
) {
    Row(
        modifier = Modifier.clickable(onClick = onUserClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarCircle(
            pubkey = article.event.pubkey,
            name = article.authorProfile?.bestName,
            pictureUrl = article.authorProfile?.picture,
            size = 24,
        )
        ProfileNameText(
            profile = article.authorProfile,
            fallback = article.event.shortPubkey,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatTimestamp(article.sortTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArticleAuthorRow(
    author: ArticleAuthorItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(
            pubkey = author.pubkey,
            name = author.profile?.bestName,
            pictureUrl = author.profile?.picture,
            size = 44,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ProfileNameText(
                profile = author.profile,
                fallback = author.pubkey.take(8),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = author.latestArticle.displayTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${author.articleCount}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "記事",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArticleDetailContent(
    article: ArticleItem,
    quotedEvents: Map<String, NostrEvent>,
    quotedProfiles: Map<String, NostrProfile>,
    loadingQuoteIds: Set<String>,
    onUserClick: (pubkey: String) -> Unit,
    onNoteClick: (eventId: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = article.displayTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        ArticleAuthorLine(article = article, onUserClick = { onUserClick(article.event.pubkey) })
        article.meta.imageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
            NetworkImage(
                url = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                maxDecodeSizePx = 1200,
            )
        }
        MarkdownBody(
            content = article.event.content,
            articleQuoteIds = quotedEventIds(article.event),
            quotedEvents = quotedEvents,
            quotedProfiles = quotedProfiles,
            loadingQuoteIds = loadingQuoteIds,
            onUserClick = onUserClick,
            onNoteClick = onNoteClick,
        )
        if (article.meta.topics.isNotEmpty()) {
            Text(
                text = article.meta.topics.joinToString("  ") { "#$it" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun String.relayDisplayName(): String =
    removePrefix("wss://").removePrefix("ws://").trimEnd('/')

private fun Modifier.articleHubSwipe(
    selectedArticleTab: ArticleHubTab,
    onArticleTabSelected: (ArticleHubTab) -> Unit,
    selectedServiceTab: ServiceTab,
    onServiceTabSelected: (ServiceTab) -> Unit,
): Modifier = pointerInput(selectedArticleTab, selectedServiceTab) {
    var dragAmount = 0f
    detectHorizontalDragGestures(
        onDragStart = { dragAmount = 0f },
        onHorizontalDrag = { change, amount ->
            dragAmount += amount
            change.consume()
        },
        onDragEnd = {
            val articleIndex = ArticleHubTab.entries.indexOf(selectedArticleTab)
            val serviceIndex = ServiceTab.entries.indexOf(selectedServiceTab)
            when {
                dragAmount < -SwipeThresholdPx && articleIndex < ArticleHubTab.entries.lastIndex ->
                    onArticleTabSelected(ArticleHubTab.entries[articleIndex + 1])
                dragAmount < -SwipeThresholdPx && serviceIndex < ServiceTab.entries.lastIndex ->
                    onServiceTabSelected(ServiceTab.entries[serviceIndex + 1])
                dragAmount > SwipeThresholdPx && articleIndex > 0 ->
                    onArticleTabSelected(ArticleHubTab.entries[articleIndex - 1])
                dragAmount > SwipeThresholdPx && serviceIndex > 0 ->
                    onServiceTabSelected(ServiceTab.entries[serviceIndex - 1])
            }
        },
        onDragCancel = { dragAmount = 0f },
    )
}

private const val SwipeThresholdPx = 80f

@Composable
internal fun MarkdownBody(
    content: String,
    articleQuoteIds: List<String>,
    quotedEvents: Map<String, NostrEvent>,
    quotedProfiles: Map<String, NostrProfile>,
    loadingQuoteIds: Set<String>,
    onUserClick: (pubkey: String) -> Unit,
    onNoteClick: (eventId: String) -> Unit,
) {
    val inlineQuoteIds = mutableSetOf<String>()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseMarkdownBlocks(content).forEach { block ->
            val quoteIds = when (block) {
                MarkdownBlock.Blank,
                is MarkdownBlock.Code,
                is MarkdownBlock.Image,
                -> emptyList()
                is MarkdownBlock.Heading -> extractNostrEventReferences(block.text).map { it.eventId }
                is MarkdownBlock.Quote -> extractNostrEventReferences(block.text).map { it.eventId }
                is MarkdownBlock.ListItem -> extractNostrEventReferences(block.text).map { it.eventId }
                is MarkdownBlock.Paragraph -> extractNostrEventReferences(block.text).map { it.eventId }
            }
            inlineQuoteIds += quoteIds
            when (block) {
                MarkdownBlock.Blank -> Box(modifier = Modifier.height(6.dp))
                is MarkdownBlock.Heading -> stripNostrEventUris(block.text).takeIf { it.isNotBlank() }?.let { text ->
                    MarkdownInlineText(
                        text = text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (block.level == 1) FontWeight.Bold else FontWeight.SemiBold,
                        headingLevel = block.level,
                    )
                }
                is MarkdownBlock.Quote -> stripNostrEventUris(block.text).takeIf { it.isNotBlank() }?.let { text ->
                    MarkdownInlineText(
                        text = text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is MarkdownBlock.ListItem -> stripNostrEventUris(block.text).takeIf { it.isNotBlank() }?.let { text ->
                    MarkdownInlineText(
                        text = "• $text",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                is MarkdownBlock.Code -> Text(
                    text = block.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is MarkdownBlock.Image -> NetworkImage(
                    url = block.url,
                    contentDescription = block.alt.takeIf { it.isNotBlank() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                    maxDecodeSizePx = 1200,
                )
                is MarkdownBlock.Paragraph -> stripNostrEventUris(block.text).takeIf { it.isNotBlank() }?.let { text ->
                    MarkdownInlineText(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            quoteIds.forEach { eventId ->
                ArticleQuotePreviewSlot(
                    eventId = eventId,
                    quotedEvents = quotedEvents,
                    quotedProfiles = quotedProfiles,
                    loadingQuoteIds = loadingQuoteIds,
                    onUserClick = onUserClick,
                    onNoteClick = onNoteClick,
                )
            }
        }
        articleQuoteIds.filterNot { it in inlineQuoteIds }.forEach { eventId ->
            ArticleQuotePreviewSlot(
                eventId = eventId,
                quotedEvents = quotedEvents,
                quotedProfiles = quotedProfiles,
                loadingQuoteIds = loadingQuoteIds,
                onUserClick = onUserClick,
                onNoteClick = onNoteClick,
            )
        }
    }
}

@Composable
private fun ArticleQuotePreviewSlot(
    eventId: String,
    quotedEvents: Map<String, NostrEvent>,
    quotedProfiles: Map<String, NostrProfile>,
    loadingQuoteIds: Set<String>,
    onUserClick: (pubkey: String) -> Unit,
    onNoteClick: (eventId: String) -> Unit,
) {
    val quotedEvent = quotedEvents[eventId]
    if (quotedEvent != null) {
        ArticleQuotePreview(
            event = quotedEvent,
            profile = quotedProfiles[quotedEvent.pubkey],
            onUserClick = onUserClick,
            onNoteClick = { onNoteClick(eventId) },
        )
    } else if (eventId in loadingQuoteIds) {
        ArticleQuoteStatusPreview("引用投稿を読み込んでいます")
    } else {
        ArticleQuoteStatusPreview("引用投稿を読み込めませんでした")
    }
}

@Composable
private fun ArticleQuotePreview(
    event: NostrEvent,
    profile: NostrProfile?,
    onUserClick: (pubkey: String) -> Unit,
    onNoteClick: () -> Unit,
) {
    val imageUrls = remember(event.content) { extractImageUrls(event.content) }
    val textContent = remember(event.content) {
        stripImageUrls(stripNostrEventUris(event.content))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            )
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onNoteClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarCircle(
                pubkey = event.pubkey,
                name = profile?.bestName,
                pictureUrl = profile?.picture,
                size = 24,
                modifier = Modifier.clickable { onUserClick(event.pubkey) },
            )
            ProfileNameText(
                profile = profile,
                fallback = event.shortPubkey,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onUserClick(event.pubkey) },
            )
            Text(
                text = formatTimestamp(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (textContent.isNotBlank()) {
            LinkedText(
                text = textContent,
                style = MaterialTheme.typography.bodySmall,
                customEmojis = event.tags.customEmojiMap(),
                onProfileClick = onUserClick,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        imageUrls.firstOrNull()?.let { imageUrl ->
            NetworkImage(
                url = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
                maxDecodeSizePx = 720,
            )
        }
    }
}

@Composable
private fun ArticleQuoteStatusPreview(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            )
            .padding(10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MarkdownInlineText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    fontWeight: FontWeight? = null,
    headingLevel: Int? = null,
) {
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = MaterialTheme.colorScheme.surfaceVariant,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val textStyle = when (headingLevel) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        else -> style
    }.let { if (fontWeight != null) it.copy(fontWeight = fontWeight) else it }

    Text(
        text = markdownAnnotatedString(text, linkStyle, codeStyle),
        modifier = modifier,
        style = textStyle,
        color = color,
    )
}

private fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val codeLines = mutableListOf<String>()
    var inCodeBlock = false

    content.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (inCodeBlock) {
            if (line.trimStart().startsWith("```")) {
                blocks += MarkdownBlock.Code(codeLines.joinToString("\n"))
                codeLines.clear()
                inCodeBlock = false
            } else {
                codeLines += rawLine
            }
            return@forEach
        }

        when {
            line.trimStart().startsWith("```") -> inCodeBlock = true
            line.isBlank() -> blocks += MarkdownBlock.Blank
            markdownImageRegex.matchEntire(line.trim()) != null -> {
                val match = markdownImageRegex.matchEntire(line.trim())!!
                blocks += MarkdownBlock.Image(
                    alt = match.groupValues[1],
                    url = match.groupValues[2],
                )
            }
            line.startsWith("### ") -> blocks += MarkdownBlock.Heading(3, line.removePrefix("### "))
            line.startsWith("## ") -> blocks += MarkdownBlock.Heading(2, line.removePrefix("## "))
            line.startsWith("# ") -> blocks += MarkdownBlock.Heading(1, line.removePrefix("# "))
            line.startsWith(">") -> blocks += MarkdownBlock.Quote(line.removePrefix(">").trim())
            line.startsWith("- ") || line.startsWith("* ") -> blocks += MarkdownBlock.ListItem(line.drop(2))
            line.startsWith("    ") -> blocks += MarkdownBlock.Code(line.trimStart())
            else -> blocks += MarkdownBlock.Paragraph(line)
        }
    }

    if (inCodeBlock) {
        blocks += MarkdownBlock.Code(codeLines.joinToString("\n"))
    }
    return blocks
}

private sealed interface MarkdownBlock {
    data object Blank : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class ListItem(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Image(val alt: String, val url: String) : MarkdownBlock
}

private val markdownImageRegex = Regex("""!\[([^]]*)]\((https://[^)\s]+)\)""")

private fun markdownAnnotatedString(
    text: String,
    linkStyle: TextLinkStyles,
    codeStyle: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    appendMarkdownInline(text, linkStyle, codeStyle)
}

private fun AnnotatedString.Builder.appendMarkdownInline(
    text: String,
    linkStyle: TextLinkStyles,
    codeStyle: SpanStyle,
) {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("`", index) -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end > index) {
                    pushStyle(codeStyle)
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(text[index])
                    index += 1
                }
            }
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end > index) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendMarkdownInline(text.substring(index + 2, end), linkStyle, codeStyle)
                    pop()
                    index = end + 2
                } else {
                    append(text[index])
                    index += 1
                }
            }
            text.startsWith("__", index) -> {
                val end = text.indexOf("__", startIndex = index + 2)
                if (end > index) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendMarkdownInline(text.substring(index + 2, end), linkStyle, codeStyle)
                    pop()
                    index = end + 2
                } else {
                    append(text[index])
                    index += 1
                }
            }
            text[index] == '*' -> {
                val end = text.indexOf('*', startIndex = index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendMarkdownInline(text.substring(index + 1, end), linkStyle, codeStyle)
                    pop()
                    index = end + 1
                } else {
                    append(text[index])
                    index += 1
                }
            }
            text[index] == '_' -> {
                val end = text.indexOf('_', startIndex = index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendMarkdownInline(text.substring(index + 1, end), linkStyle, codeStyle)
                    pop()
                    index = end + 1
                } else {
                    append(text[index])
                    index += 1
                }
            }
            text[index] == '[' -> {
                val labelEnd = text.indexOf("](", startIndex = index + 1)
                val urlEnd = if (labelEnd > index) text.indexOf(')', startIndex = labelEnd + 2) else -1
                if (labelEnd > index && urlEnd > labelEnd + 2) {
                    val url = text.substring(labelEnd + 2, urlEnd)
                    pushLink(LinkAnnotation.Url(url = url, styles = linkStyle))
                    appendMarkdownInline(text.substring(index + 1, labelEnd), linkStyle, codeStyle)
                    pop()
                    index = urlEnd + 1
                } else {
                    append(text[index])
                    index += 1
                }
            }
            else -> {
                append(text[index])
                index += 1
            }
        }
    }
}
