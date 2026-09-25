package com.nostr.torinos.ui.article

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserArticleListScreen(
    pubkey: String,
    onBack: () -> Unit,
    onArticleClick: (pubkey: String, identifier: String) -> Unit,
    onUserClick: (pubkey: String) -> Unit,
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

    // ユーザー別記事一覧のトピックは、この画面の中だけで使う。
    var topic by rememberSaveable(pubkey) { mutableStateOf<String?>(null) }
    val query = ArticleQuery(authorScope = ArticleAuthorScope.Only(pubkey), topic = topic)
    val viewModel: ArticleListViewModel = accountSessionViewModel(
        key = "user-articles-$pubkey-$activeRelayUrl",
    ) { session ->
        ArticleListViewModel(
            initialQuery = query,
            relayUrl = activeRelayUrl,
            accountSession = session,
        )
    }
    LaunchedEffect(viewModel, query) { viewModel.setQuery(query) }
    val state by viewModel.state.collectAsState()
    val listState = rememberSaveable(pubkey, selectedRelayUrl, topic, saver = LazyListState.Saver) { LazyListState() }
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            UserArticleProfileHeader(
                pubkey = pubkey,
                profile = profile,
                onClick = { onUserClick(pubkey) },
            )
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ArticleListContent(
                    state = state,
                    listState = listState,
                    onArticleClick = onArticleClick,
                    onAuthorClick = null,
                    onTopicClick = { topic = it },
                    contentPadding = PaddingValues(
                        top = articleFloatingFiltersHeight(hasAuthorFilter = false, hasTopic = topic != null),
                    ),
                    emptyText = if (topic == null) "記事がありません" else "条件に合う記事がありません",
                    modifier = Modifier.fillMaxSize(),
                )
                ArticleFloatingFilters(
                    authorFilter = null,
                    onAuthorFilterChange = {},
                    topic = topic,
                    onClearTopic = { topic = null },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun UserArticleProfileHeader(
    pubkey: String,
    profile: NostrProfile?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(
            pubkey = pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 48,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ProfileNameText(
                profile = profile,
                fallback = pubkey.take(8),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            profile?.about
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                ?.let { about ->
                    Text(
                        text = about,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
    }
}
