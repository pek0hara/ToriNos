package com.nostr.torinos.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import com.nostr.torinos.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NoteTimeline
import com.nostr.torinos.ui.components.RelaySelector
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenSettings: () -> Unit = {},
    onOpenRelaySettings: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onUserClick: (pubkey: String) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onReply: ((event: NostrEvent, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    onOpenSearch: (query: String) -> Unit = {},
    ownPubkey: String? = null,
    accountResetKey: Int = 0,
    ownProfile: NostrProfile? = null,
    isAccountLoaded: Boolean = true,
    scrollToTopRequest: Int = 0,
    scrollToTopTargetTab: FeedTab = FeedTab.Following,
    onCurrentFeedTabChanged: (FeedTab) -> Unit = {},
    requestedFeedTab: FeedTab = FeedTab.Following,
    feedTabChangeRequest: Int = 0,
    followingListState: LazyListState? = null,
    globalListState: LazyListState? = null,
    hasNotifications: Boolean = false,
    longBackgroundResetRequest: Int = 0,
    /** トップバーとボトムバーで共有する折りたたみ量。値はコンポーズ中に読まない。 */
    chromeState: FeedChromeState = remember { FeedChromeState() },
    /** 下部バーの裏までリストを描くための下側の余白。下部バーはリストに重ねて描かれる。 */
    bottomContentPadding: Dp = 0.dp,
    /**
     * false の間はクロームの折りたたみ更新を止め、表示状態（fraction 0）に固定する。
     * 簡易投稿欄が開いている間、入力欄とボトムナビがスクロールで動かないようにするために使う。
     */
    chromeCollapseEnabled: Boolean = true,
    /** null = グローバルフィード、非null = 特定ユーザーのポスト */
    authorPubkey: String? = null,
) {
    val relays by RelayStore.relays.collectAsState(
        initial = RelayStore.defaults.filter { it.enabled }.map { it.url },
    )
    val selectedFollowingRelayUrl by RelayStore.selectedFollowingRelayUrl.collectAsState()
    val selectedGlobalRelayUrl by RelayStore.selectedGlobalRelayUrl.collectAsState()
    val effectiveGlobalRelayUrl = selectedGlobalRelayUrl ?: relays.firstOrNull()
    val accountSession = LocalAccountSession.current
    val followedPubkeys = accountSession?.followRepository?.followedPubkeys?.collectAsState()?.value.orEmpty()
    val isFollowListLoaded = accountSession?.followRepository?.loaded?.collectAsState()?.value ?: true
    val mutedPubkeys = accountSession?.muteStore?.mutedPubkeys?.collectAsState()?.value.orEmpty()
    var showRelayMenu by remember { mutableStateOf(false) }
    var feedTab by rememberSaveable(accountResetKey, authorPubkey) { mutableStateOf(FeedTab.Following) }
    var followingFeedMode by rememberSaveable(accountResetKey, authorPubkey) {
        mutableStateOf(FollowingFeedMode.Following)
    }
    var handledScrollToTopRequest by remember { mutableStateOf(scrollToTopRequest) }
    val isLoggedOutMainFeed = authorPubkey == null && isAccountLoaded && ownPubkey == null
    val visibleFeedTabs = if (isLoggedOutMainFeed) listOf(FeedTab.Global) else FeedTab.entries
    val savedVisibleFeedTab = if (feedTab in visibleFeedTabs) feedTab else FeedTab.Global
    val pagerState = rememberPagerState(
        initialPage = visibleFeedTabs.indexOf(savedVisibleFeedTab).coerceAtLeast(0),
        pageCount = { visibleFeedTabs.size },
    )
    val coroutineScope = rememberCoroutineScope()
    val visibleFeedTab = visibleFeedTabs.getOrElse(pagerState.currentPage) { savedVisibleFeedTab }
    // スワイプ中は遷移元だけを購読し、ページが確定してから遷移先へ切り替える。
    val subscriptionFeedTab = visibleFeedTabs.getOrElse(pagerState.settledPage) { savedVisibleFeedTab }

    fun setFeedTab(tab: FeedTab) {
        val nextTab = if (isLoggedOutMainFeed && tab == FeedTab.Following) FeedTab.Global else tab
        val page = visibleFeedTabs.indexOf(nextTab)
        if (page >= 0) {
            coroutineScope.launch {
                pagerState.animateScrollToPage(page)
            }
        }
    }

    LaunchedEffect(accountResetKey, authorPubkey, visibleFeedTabs) {
        val targetTab = if (feedTab in visibleFeedTabs) feedTab else FeedTab.Global
        val targetPage = visibleFeedTabs.indexOf(targetTab).coerceAtLeast(0)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
        if (feedTab != targetTab) {
            feedTab = targetTab
        }
        onCurrentFeedTabChanged(targetTab)
    }

    LaunchedEffect(pagerState, visibleFeedTabs) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val tab = visibleFeedTabs.getOrNull(page) ?: return@collect
            if (feedTab != tab) {
                feedTab = tab
                onCurrentFeedTabChanged(tab)
            }
        }
    }

    LaunchedEffect(feedTabChangeRequest) {
        if (feedTabChangeRequest > 0 && authorPubkey == null) {
            val requestedTab = if (requestedFeedTab in visibleFeedTabs) requestedFeedTab else FeedTab.Global
            val requestedPage = visibleFeedTabs.indexOf(requestedTab).coerceAtLeast(0)
            pagerState.animateScrollToPage(requestedPage)
        }
    }

    // リレーリストが変わったら、各タブの選択中 URL を有効なものに補正する。
    LaunchedEffect(relays, selectedFollowingRelayUrl, selectedGlobalRelayUrl) {
        if (selectedFollowingRelayUrl != null && selectedFollowingRelayUrl !in relays) {
            RelayStore.setSelectedFollowingRelayUrl(null)
        }
        val fallbackGlobalRelayUrl = relays.firstOrNull()
        if (fallbackGlobalRelayUrl != null && (selectedGlobalRelayUrl == null || selectedGlobalRelayUrl !in relays)) {
            RelayStore.setSelectedGlobalRelayUrl(fallbackGlobalRelayUrl)
        }
    }

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest <= handledScrollToTopRequest) return@LaunchedEffect
        handledScrollToTopRequest = scrollToTopRequest
        when {
            authorPubkey != null -> Unit
            scrollToTopTargetTab == FeedTab.Following -> followingListState?.animateScrollToItem(0)
            scrollToTopTargetTab == FeedTab.Global -> globalListState?.animateScrollToItem(0)
        }
    }

    val selectedFeedRelayUrl = when {
        authorPubkey != null -> effectiveGlobalRelayUrl
        visibleFeedTab == FeedTab.Following -> null
        else -> effectiveGlobalRelayUrl
    }
    val canSelectAllRelays = authorPubkey == null && visibleFeedTab == FeedTab.Following
    val activeRelayUrl = selectedFeedRelayUrl
    var selectedRelayName by remember(selectedFeedRelayUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedFeedRelayUrl) {
        selectedRelayName = selectedFeedRelayUrl
            ?.let { RelayInformationRepository.fetch(it).getOrNull()?.name }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }
    val topBarTitle = when {
        authorPubkey != null -> selectedRelayName ?: selectedFeedRelayUrl?.relayDisplayName() ?: "—"
        visibleFeedTab == FeedTab.Following && followingFeedMode == FollowingFeedMode.Muted -> "ミュートフィード"
        selectedFeedRelayUrl == null && canSelectAllRelays -> "すべてのリレー"
        else -> selectedRelayName ?: selectedFeedRelayUrl?.relayDisplayName() ?: "—"
    }
    val feedBackgroundColor = MaterialTheme.colorScheme.background
    val feedContentColor = MaterialTheme.colorScheme.onBackground
    val activeListState = when {
        authorPubkey != null -> null
        visibleFeedTab == FeedTab.Following -> followingListState
        else -> globalListState
    }
    val density = LocalDensity.current
    // クロームはリストに重ねて描き、translationYで隠す。高さを変えないのでリストの位置は動かない。
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val chromeVisibility: () -> Float = remember(chromeState) { { chromeState.visibility } }
    val chromeSettleAnimation = remember { Animatable(0f) }
    val chromeSettleJob = remember { mutableStateOf<Job?>(null) }
    var chromeBehaviorState by remember { mutableStateOf(FeedChromeBehaviorState()) }
    val currentTopBarHeightPx = rememberUpdatedState(topBarHeightPx)
    val currentChromeCollapseEnabled = rememberUpdatedState(chromeCollapseEnabled)

    fun settleChrome(targetFraction: Float) {
        if (!currentChromeCollapseEnabled.value) return
        chromeSettleJob.value?.cancel()
        chromeSettleJob.value = coroutineScope.launch {
            val fraction = chromeState.collapseFraction
            if (fraction == targetFraction) return@launch
            chromeSettleAnimation.snapTo(fraction)
            chromeSettleAnimation.animateTo(
                targetValue = targetFraction,
                animationSpec = tween(ChromeSettleAnimationMillis),
            ) {
                chromeState.collapseFraction = value
            }
        }
    }

    val chromeNestedScrollConnection = remember(authorPubkey, activeListState, chromeState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val collapseDistancePx = currentTopBarHeightPx.value
                if (
                    !currentChromeCollapseEnabled.value ||
                    authorPubkey != null ||
                    activeListState == null ||
                    collapseDistancePx <= 0 ||
                    consumed.y == 0f
                ) {
                    return Offset.Zero
                }
                val reduce = when (source) {
                    NestedScrollSource.UserInput -> ::reduceFeedChromeUserScroll
                    // 慣性中もクロームを内容と一緒に動かす。止めると途中の透明度のまま内容に重なって残る。
                    NestedScrollSource.SideEffect -> ::reduceFeedChromeFlingScroll
                    else -> return Offset.Zero
                }
                // 指が触れている間は寄せない。寄せるのはonPostFlingだけ。
                chromeSettleJob.value?.cancel()
                val decision = reduce(
                    chromeBehaviorState,
                    -consumed.y,
                    chromeState.collapseFraction,
                    collapseDistancePx,
                    activeListState.chromeMaxFraction(collapseDistancePx),
                    FeedChromeCollapseLatchEnabled,
                )
                chromeBehaviorState = decision.state
                if (decision.nextFraction != chromeState.collapseFraction) {
                    chromeState.collapseFraction = decision.nextFraction
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!currentChromeCollapseEnabled.value) return Velocity.Zero
                val decision = reduceFeedChromePostFling(
                    state = chromeBehaviorState,
                    currentFraction = chromeState.collapseFraction,
                    isAtTop = activeListState?.isAtAbsoluteTop() == true,
                    maxFraction = activeListState?.chromeMaxFraction(currentTopBarHeightPx.value) ?: 1f,
                )
                chromeBehaviorState = decision.state
                decision.targetFraction?.let(::settleChrome)
                return Velocity.Zero
            }
        }
    }

    // 停止時は、実行中の寄せアニメーションとジェスチャー状態を捨てて表示状態へ戻す。
    // 見た目のfractionだけを0にして、背後の状態機械を動かし続けない。
    // 再開時は表示状態から始め、直前の収納率は復元しない。
    LaunchedEffect(chromeCollapseEnabled) {
        if (!chromeCollapseEnabled) {
            chromeSettleJob.value?.cancel()
            chromeBehaviorState = FeedChromeBehaviorState()
            chromeState.collapseFraction = 0f
        }
    }

    LaunchedEffect(activeListState, authorPubkey) {
        // 旧タブで始まった寄せアニメーションが、新しいタブの上限を越えて書き込まないようにする。
        chromeSettleJob.value?.cancel()
        chromeBehaviorState = reduceFeedChromeContextChanged(chromeBehaviorState)
        if (authorPubkey != null || activeListState == null) {
            chromeState.collapseFraction = 0f
            return@LaunchedEffect
        }

        snapshotFlow {
            activeListState.isAtAbsoluteTop()
        }.collect { atTop ->
            val decision = reduceFeedChromeAtTopChanged(
                state = chromeBehaviorState,
                currentFraction = chromeState.collapseFraction,
                isAtTop = atTop,
            )
            chromeBehaviorState = decision.state
            decision.targetFraction?.let(::settleChrome)
        }
    }

    // 慣性や先頭移動など、指以外でリストが先頭へ近づいたときも、クロームの下に空白を出さない。
    LaunchedEffect(activeListState, authorPubkey, topBarHeightPx) {
        if (authorPubkey != null || activeListState == null || topBarHeightPx <= 0) return@LaunchedEffect
        snapshotFlow { activeListState.chromeMaxFraction(topBarHeightPx) }
            .collect { maxFraction ->
                if (chromeState.collapseFraction > maxFraction) {
                    chromeSettleJob.value?.cancel()
                    chromeState.collapseFraction = maxFraction
                }
            }
    }

    val topBarHeight = with(density) { topBarHeightPx.toDp() }
    val timelineContentPadding = PaddingValues(top = topBarHeight, bottom = bottomContentPadding)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(feedBackgroundColor),
    ) {
                Column(
                    modifier = Modifier
                        .zIndex(1f)
                        .fillMaxWidth()
                        .onSizeChanged { topBarHeightPx = it.height }
                        .graphicsLayer {
                            val fraction = chromeState.collapseFraction
                            translationY = -size.height * fraction
                            alpha = 1f - fraction
                        }
                        .background(feedBackgroundColor),
                ) {
                AppTopBar(
                    title = {
                        if (canSelectAllRelays) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start,
                            ) {
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = topBarTitle,
                                        fontSize = 16.sp,
                                        color = feedContentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (followingFeedMode == FollowingFeedMode.Following) {
                                        Text(
                                            text = "${relays.size}リレー",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                IconButton(onClick = { showRelayMenu = true }) {
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "フィードメニュー",
                                        tint = feedContentColor,
                                    )
                                }
                                DropdownMenu(
                                    expanded = showRelayMenu,
                                    onDismissRequest = { showRelayMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("すべてのリレー") },
                                        onClick = {
                                            followingFeedMode = FollowingFeedMode.Following
                                            RelayStore.setSelectedFollowingRelayUrl(null)
                                            showRelayMenu = false
                                        },
                                        trailingIcon = if (
                                            followingFeedMode == FollowingFeedMode.Following &&
                                            selectedFeedRelayUrl == null
                                        ) {
                                            {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                )
                                            }
                                        } else null,
                                    )
                                    DropdownMenuItem(
                                        text = { Text("リレー設定") },
                                        onClick = {
                                            showRelayMenu = false
                                            onOpenRelaySettings()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("ミュートフィード") },
                                        onClick = {
                                            followingFeedMode = FollowingFeedMode.Muted
                                            RelayStore.setSelectedFollowingRelayUrl(null)
                                            showRelayMenu = false
                                        },
                                        trailingIcon = if (followingFeedMode == FollowingFeedMode.Muted) {
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
                        } else {
                            RelaySelector(
                                relays = relays,
                                selectedRelayUrl = selectedFeedRelayUrl,
                                onRelaySelected = RelayStore::setSelectedGlobalRelayUrl,
                                onOpenRelaySettings = onOpenRelaySettings,
                                selectedRelayName = selectedRelayName,
                                showSelectedRelayUrl = true,
                            )
                        }
                    },
                    navigationIcon = {
                        if (authorPubkey == null && ownPubkey != null) {
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
                    actions = {
                        if (authorPubkey == null) {
                            IconButton(onClick = { onOpenSearch("") }) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "検索",
                                    tint = feedContentColor,
                                )
                            }
                            if (ownPubkey != null) {
                                IconButton(onClick = onOpenNotifications) {
                                    Box(modifier = Modifier.size(24.dp)) {
                                        Icon(
                                            Icons.Default.Notifications,
                                            contentDescription = "通知",
                                            tint = feedContentColor,
                                        )
                                        if (hasNotifications) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(8.dp)
                                                    .background(Color.White, CircleShape)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
                if (authorPubkey == null) {
                    FeedTabRow(
                        tabs = visibleFeedTabs,
                        selectedTab = visibleFeedTab,
                        onTabSelected = { setFeedTab(it) },
                    )
                }
            }

        val timelineModifier = Modifier
            .background(feedBackgroundColor)
            .nestedScroll(chromeNestedScrollConnection)

        if (authorPubkey != null) {
            FeedTimelinePane(
                viewModelKey = "profile-$authorPubkey-${activeRelayUrl ?: "all"}",
                authorPubkey = authorPubkey,
                authorPubkeys = listOf(authorPubkey),
                relayUrl = activeRelayUrl,
                includeRepostsInFeed = false,
                hashtag = null,
                ownPubkey = ownPubkey,
                onUserClick = onUserClick,
                modifier = timelineModifier,
                contentPadding = timelineContentPadding,
                topOverlayVisibility = chromeVisibility,
                onReply = onReply,
                onOpenReplies = onOpenReplies,
                onOpenLikes = onOpenLikes,
                onOpenReposts = onOpenReposts,
                onHashtagClick = null,
                scrollToTopRequest = scrollToTopRequest,
                longBackgroundResetRequest = longBackgroundResetRequest,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = timelineModifier.fillMaxSize(),
            ) { page ->
                when (visibleFeedTabs[page]) {
                    FeedTab.Following -> {
                        val followingAuthors = when (followingFeedMode) {
                            FollowingFeedMode.Following -> followedPubkeys.sorted()
                            FollowingFeedMode.Muted -> mutedPubkeys.sorted()
                        }
                        if (followingFeedMode == FollowingFeedMode.Following && !isFollowListLoaded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(timelineContentPadding),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "読み込み中...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        } else {
                            val ownerKey = ownPubkey ?: "anonymous"
                            FeedTimelinePane(
                                // 著者一覧はキーに含めない。フォロー更新は同じViewModelへ流し込む。
                                viewModelKey = "global-${FeedTab.Following.name}-${followingFeedMode.name}-all-$ownerKey",
                                authorPubkey = null,
                                authorPubkeys = followingAuthors,
                                relayUrl = null,
                                includeRepostsInFeed = followingFeedMode == FollowingFeedMode.Following,
                                hashtag = null,
                                filterMutedUsers = followingFeedMode == FollowingFeedMode.Following,
                                ownPubkey = ownPubkey,
                                onUserClick = onUserClick,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = timelineContentPadding,
                                topOverlayVisibility = chromeVisibility,
                                onReply = onReply,
                                onOpenReplies = onOpenReplies,
                                onOpenLikes = onOpenLikes,
                                onOpenReposts = onOpenReposts,
                                onHashtagClick = { tag -> onOpenSearch("#$tag") },
                                listState = followingListState,
                                onRefresh = {
                                    if (followingFeedMode == FollowingFeedMode.Following) {
                                        accountSession?.followRepository?.refresh()
                                    }
                                },
                                isActive = subscriptionFeedTab == FeedTab.Following,
                                longBackgroundResetRequest = longBackgroundResetRequest,
                            )
                        }
                    }

                    FeedTab.Global -> {
                        val ownerKey = ownPubkey ?: "anonymous"
                        FeedTimelinePane(
                            viewModelKey = "global-${FeedTab.Global.name}-" +
                                "${effectiveGlobalRelayUrl ?: "all"}-all-false-$ownerKey",
                            authorPubkey = null,
                            authorPubkeys = null,
                            relayUrl = effectiveGlobalRelayUrl,
                            includeRepostsInFeed = false,
                            hashtag = null,
                            ownPubkey = ownPubkey,
                            onUserClick = onUserClick,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = timelineContentPadding,
                            topOverlayVisibility = chromeVisibility,
                            onReply = onReply,
                            onOpenReplies = onOpenReplies,
                            onOpenLikes = onOpenLikes,
                            onOpenReposts = onOpenReposts,
                            onHashtagClick = { tag -> onOpenSearch("#$tag") },
                            listState = globalListState,
                            isActive = subscriptionFeedTab == FeedTab.Global,
                            longBackgroundResetRequest = longBackgroundResetRequest,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedTabRow(
    tabs: List<FeedTab>,
    selectedTab: FeedTab,
    onTabSelected: (FeedTab) -> Unit,
) {
    PrimaryTabRow(
        selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = { Text(tab.label) },
            )
        }
    }
}

@Composable
private fun FeedTimelinePane(
    viewModelKey: String,
    authorPubkey: String?,
    authorPubkeys: List<String>?,
    relayUrl: String?,
    includeRepostsInFeed: Boolean,
    hashtag: String?,
    filterMutedUsers: Boolean = true,
    ownPubkey: String?,
    onUserClick: (String) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    topOverlayVisibility: () -> Float,
    onReply: ((event: NostrEvent, preview: String) -> Unit)?,
    onOpenReplies: (eventId: String) -> Unit,
    onOpenLikes: (eventId: String) -> Unit,
    onOpenReposts: (eventId: String) -> Unit,
    onHashtagClick: ((tag: String) -> Unit)?,
    scrollToTopRequest: Int = 0,
    listState: LazyListState? = null,
    onRefresh: (() -> Unit)? = null,
    isActive: Boolean = true,
    longBackgroundResetRequest: Int = 0,
) {
    val viewModel: FeedViewModel = accountSessionViewModel(
        key = viewModelKey,
    ) { accountSession ->
        FeedViewModel(
            accountSession = accountSession,
            authorPubkey = authorPubkey,
            authorPubkeys = authorPubkeys,
            relayUrl = relayUrl,
            autoStart = false,
            includeRepostsInFeed = includeRepostsInFeed,
            hashtag = hashtag,
            filterMutedUsers = filterMutedUsers,
        )
    }
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var resetToTopRequest by remember(viewModel) { mutableIntStateOf(0) }
    var shouldStageInitialEvents by remember(viewModel) {
        mutableStateOf(viewModel.state.value.isInitialLoad)
    }

    LaunchedEffect(viewModel, authorPubkeys) {
        if (viewModel.updateAuthors(authorPubkeys)) {
            shouldStageInitialEvents = true
            resetToTopRequest++
        }
    }

    LaunchedEffect(state.engagementError) {
        val error = state.engagementError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.consumeEngagementError()
    }

    if (isActive) {
        LifecycleStartEffect(viewModel, longBackgroundResetRequest) {
            if (viewModel.resetToLatest(longBackgroundResetRequest)) {
                shouldStageInitialEvents = true
                resetToTopRequest++
            }
            viewModel.startSubscriptions()
            onStopOrDispose {
                viewModel.stopSubscriptions()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NoteTimeline(
            state = state,
            ownPubkey = ownPubkey,
            onUserClick = onUserClick,
            onLoadMore = viewModel::loadMore,
            onLike = viewModel::react,
            onUnlike = viewModel::unreact,
            onEmojiReact = viewModel::reactWithEmoji,
            onEmojiUnreact = viewModel::unreactWithEmoji,
            onDelete = viewModel::deleteEvent,
            modifier = modifier,
            contentPadding = contentPadding,
            topOverlayVisibility = topOverlayVisibility,
            onReply = onReply,
            onOpenReplies = onOpenReplies,
            onOpenLikes = onOpenLikes,
            onOpenReposts = onOpenReposts,
            onRefreshReactions = viewModel::refreshReactions,
            onRepost = viewModel::repost,
            onUnrepost = viewModel::unrepost,
            onReport = viewModel::reportEvent,
            onHashtagClick = onHashtagClick,
            scrollToTopRequest = scrollToTopRequest,
            resetToTopRequest = resetToTopRequest,
            listState = listState,
            isRefreshing = state.isRefreshing,
            emptyStateDelayMillis = 500L,
            eventEnterFadeMillis = 150,
            stageInitialEvents = shouldStageInitialEvents,
            onAtTopChanged = viewModel::setAtTop,
            atTopReportKey = viewModel,
            onRefresh = {
                onRefresh?.invoke()
                viewModel.refresh()
            },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        )
    }
}

enum class FeedTab(val label: String) {
    Following("フォロー"),
    Global("グローバル"),
}

private enum class FollowingFeedMode {
    Following,
    Muted,
}

private const val ChromeSettleAnimationMillis = 140

private fun LazyListState.isAtAbsoluteTop(): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/** 先頭の項目が見えている間は、その位置からクロームを隠せる量の上限を求める。 */
private fun LazyListState.chromeMaxFraction(collapseDistancePx: Int): Float {
    val first = layoutInfo.visibleItemsInfo.firstOrNull()
    val scrolledFromTopPx = when {
        first == null -> 0
        first.index != 0 -> null
        else -> (-first.offset).coerceAtLeast(0)
    }
    return feedChromeMaxFraction(scrolledFromTopPx, collapseDistancePx)
}

private fun String.relayDisplayName(): String =
    removePrefix("wss://").removePrefix("ws://").trimEnd('/')
