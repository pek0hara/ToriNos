package com.nostr.torinos.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.RelaySelector
import com.nostr.torinos.ui.profile.AvatarCircle
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
    val accountSession = LocalAccountSession.current

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
    var selectedTab by rememberSaveable { mutableStateOf(ArticleHubTab.Articles) }
    val followedPubkeys = accountSession?.followRepository?.followedPubkeys?.collectAsState()?.value.orEmpty()
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
