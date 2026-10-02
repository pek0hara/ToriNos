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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.EmojiAdoptionState
import com.nostr.torinos.emoji.EmojiSetDiscoveryState
import com.nostr.torinos.ui.components.RelaySelector
import com.nostr.torinos.network.RelayStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import com.nostr.torinos.emoji.EmojiSetDiscovery
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.rememberEmojiPreferences

/** カスタム絵文字の設定画面。公開セットの検索・登録と、登録済みセットの管理を1つの一覧で行う。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomEmojiSettingsScreen(
    onBack: () -> Unit = {},
    initialQuery: String = "",
    initialImageUrl: String = "",
    initialSetAddress: EmojiSetAddress? = null,
    serviceHeader: (@Composable () -> Unit)? = null,
    onOpenRelaySettings: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    returnToSourceOnDetailBack: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val account = LocalAccountSession.current
    val repository = account?.customEmojis
    val preferences = rememberEmojiPreferences()
    val selectedRelay by RelayStore.selectedEmojiRelayUrl.collectAsState()
    val relaysLoaded by RelayStore.isLoaded.collectAsState()
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val discoverySnapshot by EmojiSetDiscovery.state.collectAsState()
    val discovery = discoverySnapshot.takeIf { it.relayUrl == selectedRelay }
        ?: EmojiSetDiscoveryState(relayUrl = selectedRelay)
    val emptyAdoption = remember { MutableStateFlow(EmojiAdoptionState()) }
    val adoptionSnapshot by (account?.emojiAdoption?.state ?: emptyAdoption).collectAsState()
    val emptyFollows = remember { MutableStateFlow(emptySet<String>()) }
    val loadedFallback = remember { MutableStateFlow(true) }
    val follows by (account?.followRepository?.followedPubkeys ?: emptyFollows).collectAsState()
    val followsLoaded by (account?.followRepository?.loaded ?: loadedFallback).collectAsState()
    val adoption = adoptionSnapshot.takeIf { it.relayUrl == selectedRelay && it.follows == follows - setOfNotNull(account?.pubkey) }
        ?: EmojiAdoptionState()
    val counts = remember(adoption.latest) { adoption.counts }
    var sort by rememberSaveable { mutableStateOf(EmojiSetSort.Adoption) }
    val effectiveSort = if (adoption.latest.isEmpty()) EmojiSetSort.Newest else sort
    var referenceBudget by remember(selectedRelay, account?.sessionId) { mutableStateOf(1_000) }
    val discoverListState = rememberLazyListState()
    var orderedKeys by remember(selectedRelay, account?.sessionId) { mutableStateOf(emptyList<String>()) }
    var requestedSetFailed by remember { mutableStateOf(false) }
    var directFetchAttempt by remember { mutableStateOf(0) }
    var previousRelay by remember { mutableStateOf(selectedRelay) }
    var discoverQuery by rememberSaveable { mutableStateOf(initialQuery) }
    var showRegisteredOnly by rememberSaveable { mutableStateOf(false) }
    // 検索欄は普段は畳んでおき、検索語を渡されて開いたときだけ最初から出す。
    var searchOpen by rememberSaveable { mutableStateOf(initialQuery.isNotBlank()) }
    var openedSet by remember { mutableStateOf<EmojiSetView?>(null) }
    var didOpenRequestedSet by remember(initialQuery, initialImageUrl, initialSetAddress) { mutableStateOf(false) }
    val filteredPublishedSets = remember(discovery.publishedSets, preferences.sets, discoverQuery, showRegisteredOnly) {
        discovery.publishedSets.filterEmojiSets(discoverQuery, showRegisteredOnly, preferences.sets)
    }

    // 公開セット一覧はリレーごとに共有し、フォローの増減では取り直さない。
    DisposableEffect(selectedRelay, relaysLoaded) {
        val release = if (relaysLoaded) selectedRelay?.let(EmojiSetDiscovery::acquire) else null
        onDispose { release?.invoke() }
    }
    // 登録人数の取得はセッションで共有するので、最後の画面が離れたときだけ止める。
    DisposableEffect(account?.emojiAdoption) {
        val release = account?.emojiAdoption?.acquire()
        onDispose { release?.invoke() }
    }
    LaunchedEffect(selectedRelay, relaysLoaded, followsLoaded, follows) {
        if (relaysLoaded && selectedRelay != null && followsLoaded) account?.emojiAdoption?.start(selectedRelay!!, follows)
    }
    LaunchedEffect(selectedRelay) {
        if (previousRelay != selectedRelay) {
            openedSet = null
            didOpenRequestedSet = false
            requestedSetFailed = false
            previousRelay = selectedRelay
            discoverListState.scrollToItem(0)
        }
    }
    LaunchedEffect(selectedRelay, adoption.isLoading, adoption.latest, discovery.isLoading, referenceBudget) {
        if (selectedRelay != null && !adoption.isLoading && !discovery.isLoading && counts.isNotEmpty()) {
            val addresses = counts.entries.sortedWith(
                compareByDescending<Map.Entry<EmojiSetAddress, Int>> { it.value }.thenBy { it.key.value }
            ).map { it.key }
            val newAddresses = addresses.filter { address -> discovery.publishedSets.none { it.address == address } }
                .take((referenceBudget - discovery.publishedSets.size).coerceAtLeast(0))
            EmojiSetDiscovery.fetchReferences(newAddresses)
        }
    }
    val desiredSets = remember(filteredPublishedSets, discoverQuery, effectiveSort, counts) {
        filteredPublishedSets.sortForDiscovery(discoverQuery, effectiveSort, counts)
    }
    val desiredKeys = remember(desiredSets) { desiredSets.map { it.address.value } }
    val pendingOrder = orderedKeys.isNotEmpty() && orderedKeys != desiredKeys &&
        !discovery.isLoading && !discovery.isLoadingReferences && !adoption.isLoading
    LaunchedEffect(desiredKeys, discovery.isLoading, discovery.isLoadingReferences, adoption.isLoading) {
        val isIdle = !discovery.isLoading && !discovery.isLoadingReferences && !adoption.isLoading
        // スクロール位置は Composition で読まず、先頭で止まった時だけ並び替えを反映する。
        snapshotFlow {
            !discoverListState.isScrollInProgress && discoverListState.firstVisibleItemIndex == 0 &&
                discoverListState.firstVisibleItemScrollOffset == 0
        }.distinctUntilChanged().collect { isAtRestOnTop ->
            orderedKeys = reconcileEmojiSetOrder(orderedKeys, desiredKeys,
                apply = orderedKeys.isEmpty() || (isIdle && isAtRestOnTop))
        }
    }
    val isListLoading = discovery.isLoading || discovery.isLoadingReferences || adoption.isLoading
    val positions = remember(orderedKeys) { orderedKeys.withIndex().associate { it.value to it.index } }
    val pendingReferences = remember(counts, discovery.publishedSets, discovery.failedReferences) {
        val published = discovery.publishedSets.mapTo(HashSet()) { it.address }
        counts.keys.count { address -> address !in published && address !in discovery.failedReferences }
    }
    val visibleSets = remember(desiredSets, positions) { desiredSets.sortedBy { positions[it.address.value] ?: Int.MAX_VALUE } }
    fun refresh() {
        directFetchAttempt++
        EmojiSetDiscovery.refresh()
        if (selectedRelay != null && followsLoaded) account?.emojiAdoption?.start(selectedRelay!!, follows, force = true)
    }

    LaunchedEffect(initialQuery, initialImageUrl, initialSetAddress, preferences.sets, discovery, selectedRelay) {
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
    LaunchedEffect(initialSetAddress, selectedRelay, relaysLoaded, directFetchAttempt) {
        val address = initialSetAddress ?: return@LaunchedEffect
        if (repository?.preferences?.value?.isRegistered(address) == true) return@LaunchedEffect
        if (!relaysLoaded || selectedRelay == null) return@LaunchedEffect
        val fetched = EmojiSetDiscovery.fetch(address)
        if (fetched == null) { requestedSetFailed = true; return@LaunchedEffect }
        requestedSetFailed = false
        if (!didOpenRequestedSet) {
            openedSet = fetched.toView()
            didOpenRequestedSet = true
        }
    }

    val onDetailBack: () -> Unit = {
        if (returnToSourceOnDetailBack && didOpenRequestedSet) {
            onBack()
        } else {
            openedSet = null
        }
    }
    CustomEmojiSettingsBackHandler(enabled = openedSet != null, onBack = onDetailBack)

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
            onBack = onDetailBack,
            onOpenProfile = onOpenProfile,
            serviceHeader = serviceHeader,
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (serviceHeader != null) {
                serviceHeader()
            } else {
                AppTopBar(
                    title = {
                        RelaySelector(relays = relays, selectedRelayUrl = selectedRelay,
                            onRelaySelected = RelayStore::setSelectedEmojiRelayUrl,
                            onOpenRelaySettings = onOpenRelaySettings,
                            menuDescription = "セットと公開登録情報の取得元")
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "戻る",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = ::refresh, enabled = selectedRelay != null && !discovery.isLoading) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "再読み込み",
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (searchOpen) {
                EmojiSearchField(
                    value = discoverQuery,
                    onValueChange = { discoverQuery = it; orderedKeys = emptyList() },
                    placeholder = "セット名・絵文字名で検索",
                )
            }

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), state = discoverListState) {
                item {
                    EmojiDiscoveryControls(
                        sort = effectiveSort,
                        onSort = { sort = it; orderedKeys = emptyList() },
                        count = filteredPublishedSets.size,
                        loading = isListLoading,
                        enabled = relaysLoaded && selectedRelay != null,
                        onRefresh = ::refresh,
                        notice = when {
                            !relaysLoaded -> "リレー設定を読み込み中…"
                            selectedRelay == null -> "閲覧リレーを選択してください"
                            else -> null
                        },
                        searchOpen = searchOpen,
                        onToggleSearch = {
                            // 畳んだあとに見えない検索語で絞り込まれたままにならないよう、閉じるときに消す。
                            if (searchOpen && discoverQuery.isNotEmpty()) { discoverQuery = ""; orderedKeys = emptyList() }
                            searchOpen = !searchOpen
                        },
                        onStop = if (adoption.isLoading) ({ account?.emojiAdoption?.stop() }) else null,
                        pendingOrder = pendingOrder,
                        onApplyOrder = { orderedKeys = desiredKeys },
                    )
                }
                if (discovery.failed || requestedSetFailed) item {
                    Text(
                        if (requestedSetFailed) "このリレーでは指定したセットを取得できませんでした。リレーを変更するか再試行してください。"
                        else "一覧を取得できませんでした。前回の情報がある場合は継続表示しています。",
                        modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                }
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
                            onClick = { showRegisteredOnly = !showRegisteredOnly; orderedKeys = emptyList() },
                            label = { Text("登録済みのみ (${preferences.sets.size})") },
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
                    }
                }

                // 「登録済みのみ」は端末内の登録内容だけで出せるので、取得中でも空表示を待たない。
                if (filteredPublishedSets.isEmpty() && (showRegisteredOnly || !isListLoading)) {
                    item {
                        EmptyText(
                            when {
                                !showRegisteredOnly -> "公開絵文字セットが見つかりません"
                                preferences.sets.isEmpty() -> "登録済みのセットはありません"
                                else -> "登録済みセットが見つかりません"
                            },
                        )
                    }
                } else {
                    items(visibleSets, key = { "published-${it.address.value}" }) { set ->
                        PublishedEmojiSetRow(
                            set = set,
                            isRegistered = preferences.isRegistered(set.address),
                            canRegister = repository != null,
                            onRegister = { repository?.registerSet(set.toView().toRegisteredSet()) },
                            onClick = { openedSet = set.toView() },
                            adoptionLabel = emojiAdoptionLabel(counts[set.address] ?: 0,
                                adoption.latest.isNotEmpty(), adoption.isLoading),
                        )
                        HorizontalDivider()
                    }
                }
                if (!showRegisteredOnly && pendingReferences > 0 && !discovery.isLoadingReferences && !adoption.isLoading) item {
                    TextButton(onClick = { referenceBudget = (referenceBudget + 100).coerceAtMost(3_000) },
                        enabled = referenceBudget < 3_000) {
                        Text(if (referenceBudget >= 3_000) "表示上限3,000セットに達しました"
                            else "さらに読み込む（未取得${pendingReferences}セット）")
                    }
                }
            }
        }
    }
}
