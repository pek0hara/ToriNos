package com.nostr.torinos.ui.article

import com.nostr.torinos.ui.post.PostQuote
import com.nostr.torinos.ui.post.articleQuote
import com.nostr.torinos.model.MATOMETR_TOPIC
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.nostr.torinos.network.LocalSettingsStorage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.RelaySelector
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.service.ServiceHeaderDivider
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleHubScreen(
    ownPubkey: String?,
    onOpenDrafts: () -> Unit = {},
    ownProfile: NostrProfile?,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onArticleClick: (pubkey: String, identifier: String) -> Unit,
    onAuthorClick: (pubkey: String) -> Unit,
    authorFilter: ArticleAuthorFilter,
    onAuthorFilterChange: (ArticleAuthorFilter) -> Unit,
    topic: String?,
    onTopicChange: (String?) -> Unit,
    onShareToFeed: ((PostQuote) -> Unit)? = null,
    /** false の間は描画だけして、一覧や件数を取得しない。 */
    isActive: Boolean = true,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedArticleRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()

    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        Column {
            if (ownPubkey != null) androidx.compose.material3.TextButton(onClick = onOpenDrafts) { androidx.compose.material3.Text("記事下書き") }
            RelaySelectionPendingContent(
                isLoaded = isRelayStoreLoaded,
                hasEnabledRelays = relays.isNotEmpty(),
            )
        }
        return
    }

    val query = ArticleQuery(
        authorScope = authorFilter.toAuthorScope(ownPubkey),
        topic = topic,
    )
    val viewModel: ArticleListViewModel = accountSessionViewModel(key = "article-hub-$activeRelayUrl") { session ->
        ArticleListViewModel(initialQuery = query, relayUrl = activeRelayUrl, accountSession = session, autoStart = false)
    }
    LaunchedEffect(viewModel, query) { viewModel.setQuery(query) }
    LaunchedEffect(viewModel, isActive) { if (isActive) viewModel.start() }
    val state by viewModel.state.collectAsState()
    // Remember topics the user filtered by, whether chosen from the menu or tapped on a card.
    var recentTags by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { recentTags = loadRecentArticleTags() }
    LaunchedEffect(topic, recentTags == null) {
        // Wait for the stored list, or saving the current topic would overwrite it.
        val loaded = recentTags ?: return@LaunchedEffect
        val chosen = topic ?: return@LaunchedEffect
        if (chosen.equals(MATOMETR_TOPIC, ignoreCase = true)) return@LaunchedEffect
        val next = (listOf(chosen) + loaded.filterNot { it.equals(chosen, ignoreCase = true) }).take(RecentTagLimit)
        if (next == loaded) return@LaunchedEffect
        recentTags = next
        saveRecentArticleTags(next)
    }
    val listState = rememberSaveable(activeRelayUrl, query, saver = LazyListState.Saver) { LazyListState() }
    // 未ログイン時は著者セグメントを出さず「すべて」に固定する。
    val showAuthorFilter = ownPubkey != null
    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground

    LaunchedEffect(listState, state.canLoadMore, state.isLoadingMore) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            shouldLoadMoreArticles(
                lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                totalItemsCount = layoutInfo.totalItemsCount,
                canLoadMore = state.canLoadMore,
                isLoadingMore = state.isLoadingMore,
            )
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
                        RelaySelector(
                            relays = relays,
                            selectedRelayUrl = selectedRelayUrl,
                            onRelaySelected = RelayStore::setSelectedArticleRelayUrl,
                            onOpenRelaySettings = onOpenRelaySettings,
                        )
                    },
                    actions = {
                        if (ownPubkey != null) {
                            // アイコンだけでは用途が伝わりにくいので文字を添える。
                            androidx.compose.material3.TextButton(
                                onClick = onOpenDrafts,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            ) {
                                Icon(Icons.Default.EditNote, contentDescription = null, tint = headerContentColor,
                                    modifier = Modifier.size(20.dp))
                                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                                androidx.compose.material3.Text("下書き", color = headerContentColor,
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "設定",
                                tint = headerContentColor,
                            )
                        }
                    },
                )
                ServiceHeaderDivider()
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ArticleListContent(
                state = state,
                listState = listState,
                onArticleClick = onArticleClick,
                onAuthorClick = onAuthorClick,
                onTopicClick = onTopicChange,
                engagementFor = viewModel::engagementFlow,
                onVisibleArticlesChanged = if (isActive) viewModel::onVisibleArticlesChanged else null,
                contentPadding = PaddingValues(
                    top = articleFloatingFiltersHeight(
                        hasAuthorFilter = showAuthorFilter,
                        hasTopic = topic != null,
                        hasTagSelector = true,
                    ),
                ),
                hiddenTopic = topic,
                onShareToFeed = onShareToFeed?.let { share ->
                    { article -> share(articleQuote(article.event.pubkey, article.meta.identifier, article.displayTitle, activeRelayUrl)) }
                },
                emptyText = when {
                    query == ArticleQuery() -> "記事がありません"
                    query == ArticleQuery(authorScope = ArticleAuthorScope.Following) ->
                        "フォロー中のユーザーの記事がありません"
                    else -> "条件に合う記事がありません"
                },
                modifier = Modifier.fillMaxSize(),
            )
            ArticleFloatingFilters(
                authorFilter = authorFilter.takeIf { showAuthorFilter },
                onAuthorFilterChange = onAuthorFilterChange,
                topic = topic,
                onClearTopic = { onTopicChange(null) },
                tagCandidates = remember(state.articles, recentTags) { articleTagCandidates(state.articles, recentTags.orEmpty()) },
                onSelectTag = onTopicChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val RecentTagLimit = 5
private const val RecentTagsKey = "article_recent_tags"

private suspend fun loadRecentArticleTags(): List<String> =
    runCatching { LocalSettingsStorage.getString(RecentTagsKey) }.getOrNull()
        ?.split('\n')?.filter { it.isNotBlank() }?.take(RecentTagLimit).orEmpty()

private suspend fun saveRecentArticleTags(tags: List<String>) {
    runCatching { LocalSettingsStorage.putString(RecentTagsKey, tags.joinToString("\n")) }
}
