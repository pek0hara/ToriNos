package com.nostr.torinos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.EmojiSetDiscovery
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.rememberEmojiPreferences
import kotlinx.coroutines.launch

/** カスタム絵文字の設定画面。公開セットの検索・登録と、登録済みセットの管理を行う。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomEmojiSettingsScreen(
    onBack: () -> Unit = {},
    initialQuery: String = "",
    initialImageUrl: String = "",
    initialSetAddress: EmojiSetAddress? = null,
) {
    val repository = LocalAccountSession.current?.customEmojis
    val preferences = rememberEmojiPreferences()
    val discovery by EmojiSetDiscovery.state.collectAsState()
    var discoverQuery by remember { mutableStateOf(initialQuery) }
    var registeredQuery by remember { mutableStateOf("") }
    var showRegisteredOnly by remember { mutableStateOf(false) }
    var openedSet by remember { mutableStateOf<EmojiSetView?>(null) }
    var didOpenRequestedSet by remember(initialQuery, initialImageUrl, initialSetAddress) { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { EmojiSettingsTab.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = EmojiSettingsTab.entries[pagerState.currentPage]
    val filteredPublishedSets = remember(discovery.publishedSets, preferences.sets, discoverQuery, showRegisteredOnly) {
        discovery.publishedSets.filterPublishedSets(discoverQuery, showRegisteredOnly, preferences::isRegistered)
    }
    val filteredRegisteredSets = remember(preferences.sets, registeredQuery) {
        preferences.sets.filterRegisteredSets(registeredQuery)
    }

    LaunchedEffect(Unit) { EmojiSetDiscovery.ensureLoaded() }

    LaunchedEffect(initialQuery, initialImageUrl, initialSetAddress, preferences.sets, discovery) {
        if (didOpenRequestedSet) return@LaunchedEffect
        val found = findRequestedEmojiSet(
            shortcode = initialQuery,
            imageUrl = initialImageUrl,
            address = initialSetAddress,
            registered = preferences.sets,
            published = discovery.publishedSets,
            isPublishedLoading = discovery.isLoading,
        )
        if (found != null) {
            openedSet = found
            didOpenRequestedSet = true
        }
    }

    // 公開一覧に無い古いセットは、アドレスを指定して取りに行く。
    LaunchedEffect(initialSetAddress) {
        val address = initialSetAddress ?: return@LaunchedEffect
        if (repository?.preferences?.value?.isRegistered(address) == true) return@LaunchedEffect
        val fetched = EmojiSetDiscovery.fetch(address) ?: return@LaunchedEffect
        if (!didOpenRequestedSet) {
            openedSet = fetched.toView()
            didOpenRequestedSet = true
        }
    }

    CustomEmojiSettingsBackHandler(enabled = openedSet != null) {
        openedSet = null
    }

    openedSet?.let { set ->
        val isRegistered = preferences.isRegistered(set.address)
        EmojiSetDetailScreen(
            title = set.title,
            emojis = set.emojis,
            authorPubkey = set.address.author,
            initialShortcode = initialQuery,
            initialImageUrl = initialImageUrl,
            isRegistered = isRegistered,
            canEdit = repository != null,
            isFavorite = preferences::isFavorite,
            onToggleFavorite = { emoji -> repository?.toggleFavorite(emoji) },
            onRegister = { repository?.registerSet(set.toRegisteredSet()) },
            onUnregister = { repository?.unregisterSet(set.address) },
            onBack = { openedSet = null },
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = { Text("カスタム絵文字設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = EmojiSetDiscovery::refresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "再読み込み",
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
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                EmojiSettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(tab.ordinal)
                            }
                        },
                        text = {
                            Text(
                                when (tab) {
                                    EmojiSettingsTab.Discover -> "セットを探す"
                                    EmojiSettingsTab.Registered -> "登録済み (${preferences.sets.size})"
                                },
                            )
                        },
                    )
                }
            }

            EmojiSearchField(
                value = when (selectedTab) {
                    EmojiSettingsTab.Discover -> discoverQuery
                    EmojiSettingsTab.Registered -> registeredQuery
                },
                onValueChange = { value ->
                    when (selectedTab) {
                        EmojiSettingsTab.Discover -> discoverQuery = value
                        EmojiSettingsTab.Registered -> registeredQuery = value
                    }
                },
                placeholder = when (selectedTab) {
                    EmojiSettingsTab.Discover -> "セット名・絵文字名で検索"
                    EmojiSettingsTab.Registered -> "登録済みセットを検索"
                },
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
            ) { page ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    when (EmojiSettingsTab.entries[page]) {
                        EmojiSettingsTab.Discover -> {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FilterChip(
                                        selected = showRegisteredOnly,
                                        onClick = { showRegisteredOnly = !showRegisteredOnly },
                                        label = { Text("登録済みのみ") },
                                        leadingIcon = if (showRegisteredOnly) {
                                            {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                    Text(
                                        text = if (discovery.isLoading) {
                                            "読み込み中…"
                                        } else {
                                            "${filteredPublishedSets.size}件"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (!discovery.isLoading && filteredPublishedSets.isEmpty()) {
                                item {
                                    EmptyText(
                                        if (showRegisteredOnly) {
                                            "登録済みの絵文字セットはありません"
                                        } else {
                                            "公開絵文字セットが見つかりません"
                                        },
                                    )
                                }
                            } else {
                                items(filteredPublishedSets, key = { "published-${it.address.value}" }) { set ->
                                    PublishedEmojiSetRow(
                                        set = set,
                                        isRegistered = preferences.isRegistered(set.address),
                                        canRegister = repository != null,
                                        onRegister = { repository?.registerSet(set.toView().toRegisteredSet()) },
                                        onClick = { openedSet = set.toView() },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }

                        EmojiSettingsTab.Registered -> {
                            item {
                                SectionHeader(
                                    title = "登録済みセット",
                                    trailing = if (registeredQuery.isBlank()) {
                                        "${preferences.sets.size}件・${preferences.sets.sumOf { it.emojis.size }}個"
                                    } else {
                                        "${filteredRegisteredSets.size}/${preferences.sets.size}件"
                                    },
                                )
                            }

                            if (filteredRegisteredSets.isEmpty()) {
                                item {
                                    EmptyText(
                                        if (preferences.sets.isEmpty()) {
                                            "登録済みのセットはありません\n「セットを探す」から追加できます"
                                        } else {
                                            "登録済みセットが見つかりません"
                                        },
                                    )
                                }
                            } else {
                                items(filteredRegisteredSets, key = { "registered-${it.address.value}" }) { set ->
                                    RegisteredEmojiSetRow(
                                        set = set,
                                        onClick = { openedSet = set.toView() },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class EmojiSettingsTab {
    Discover,
    Registered,
}
