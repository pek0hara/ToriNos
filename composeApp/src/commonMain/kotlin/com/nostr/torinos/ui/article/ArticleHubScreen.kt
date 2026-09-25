package com.nostr.torinos.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
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
import com.nostr.torinos.ui.service.ServiceTab
import com.nostr.torinos.ui.service.ServiceTabRow
import com.nostr.torinos.ui.service.serviceTabSwipe
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

    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        RelaySelectionPendingContent(
            isLoaded = isRelayStoreLoaded,
            hasEnabledRelays = relays.isNotEmpty(),
        )
        return
    }

    val viewModel: ArticleListViewModel = accountSessionViewModel(key = "article-hub-$activeRelayUrl") { session ->
        ArticleListViewModel(query = ArticleQuery.Global, relayUrl = activeRelayUrl, accountSession = session)
    }
    val state by viewModel.state.collectAsState()
    val listState = rememberSaveable(activeRelayUrl, saver = LazyListState.Saver) { LazyListState() }
    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground

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
        ArticleListContent(
            state = state,
            listState = listState,
            onArticleClick = onArticleClick,
            onAuthorClick = onAuthorClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .serviceTabSwipe(
                    selectedTab = selectedServiceTab,
                    onTabSelected = onServiceTabSelected,
                ),
        )
    }
}
