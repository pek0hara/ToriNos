package com.nostr.torinos.ui.post

import com.nostr.torinos.ui.components.*

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.crypto.isWebPlatform
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.journal.JournalActivity
import com.nostr.torinos.journal.JournalActivityKind
import com.nostr.torinos.journal.activityTargetId
import com.nostr.torinos.journal.availableJournalKinds
import com.nostr.torinos.journal.defaultJournalKinds
import com.nostr.torinos.journal.embeddedRepostTarget
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.article.LocalMatometrSelectionEnabled
import com.nostr.torinos.ui.components.AppFloatingActionButton
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.DeleteNoteDialog
import com.nostr.torinos.ui.components.NoteSkeletonList
import com.nostr.torinos.ui.components.QuotedEvent
import com.nostr.torinos.ui.components.RelaySelector
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    onBack: () -> Unit,
    toggleCalendarRequest: Int = 0,
    onOpenThread: (eventId: String) -> Unit = {},
    onReply: ((event: NostrEvent, preview: String) -> Unit)? = null,
    onUserClick: (pubkey: String) -> Unit = {},
    ownPubkey: String? = null,
    accountKey: String? = ownPubkey,
    ownProfile: NostrProfile? = null,
    onOpenRelaySettings: () -> Unit = {},
    targetPubkey: String? = null,
    showCalendarToggleFab: Boolean = true,
    viewModel: JournalViewModel = accountSessionViewModel(
        key = targetPubkey?.let { "journal-$it" } ?: "journal-${accountKey ?: "anonymous"}",
    ) { accountSession ->
        JournalViewModel(targetPubkey, accountSession = accountSession)
    },
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedMemoRelayUrl.collectAsState()
    var showFilterHeader by rememberSaveable(accountKey, targetPubkey) { mutableStateOf(true) }
    var selectedKindNames by rememberSaveable(accountKey, targetPubkey) {
        mutableStateOf(emptyList<String>())
    }
    val isUserJournal = targetPubkey != null
    val availableKinds = remember { availableJournalKinds() }
    var showMemberSheet by remember { mutableStateOf(false) }
    val explicitlySelectedKinds = remember(selectedKindNames, availableKinds) {
        journalKindsFromNames(selectedKindNames, availableKinds)
    }
    val visibleEntries = state.visibleEntries
    val journalListState = rememberLazyListState()
    // 引っ張って更新したか。そのときは出している一覧を残し、スケルトンに替えない（引っ張りの表示だけにする）。
    var pullRefreshRequested by remember(accountKey, targetPubkey) { mutableStateOf(false) }
    val isListLoading = isJournalListLoading(state.isLoading, state.isBackfilling, state.showCalendar)
    val isPullRefreshing = pullRefreshRequested && isListLoading
    LaunchedEffect(isListLoading) {
        if (!isListLoading) pullRefreshRequested = false
    }
    var calendarCollapsed by rememberSaveable(accountKey, targetPubkey) { mutableStateOf(false) }
    // 今日へ合わせ済みの月。詳細から戻って画面を作り直したときに、読み返していた位置から飛ばさない。
    var todayScrolledMonth by rememberSaveable(accountKey, targetPubkey) { mutableStateOf<String?>(null) }
    // 日表示から月表示へ切り替えたか。切り替えたときは今日ではなく、日表示で見ていた行から見せる。
    var listEnteredFromDay by rememberSaveable(accountKey, targetPubkey) { mutableStateOf(false) }
    // 今月を開いたとき、一覧の最初の配置の前に今日の位置へ合わせる（先頭で1コマ描いてから跳ばないように）。
    val pendingTodayScroll = remember { booleanArrayOf(false) }
    // 日表示で先頭に見えていた行と、その行の中のずれ。
    var dayViewAnchor by remember(accountKey, targetPubkey) { mutableStateOf<JournalDayViewAnchor?>(null) }
    val calendarCollapseConnection = remember(journalListState, accountKey, targetPubkey) {
        JournalCalendarCollapseConnection(
            listState = journalListState,
            isCollapsed = { calendarCollapsed },
            setCollapsed = { calendarCollapsed = it },
        )
    }

    LaunchedEffect(visibleEntries, journalListState) {
        if (visibleEntries.isEmpty()) {
            viewModel.setVisibleNoteIds(emptySet())
            return@LaunchedEffect
        }
        snapshotFlow {
            val visibleItems = journalListState.layoutInfo.visibleItemsInfo
            visibleItems.firstOrNull()?.index to visibleItems.lastOrNull()?.index
        }.collectLatest { (firstVisible, lastVisible) ->
            delay(JournalVisibleEngagementDelayMs)
            if (firstVisible == null || lastVisible == null) return@collectLatest
            val fromIndex = (firstVisible - JournalEngagementPrefetchItems).coerceAtLeast(0)
            val toIndex = (lastVisible + JournalEngagementPrefetchItems)
                .coerceAtMost(visibleEntries.lastIndex)
            val noteIds = (fromIndex..toIndex).mapNotNullTo(mutableSetOf()) { index ->
                visibleEntries[index].event
                    .takeIf { it.kind == 1 || it.kind == COMMENT_EVENT_KIND }
                    ?.id
            }
            viewModel.setVisibleNoteIds(noteIds)
        }
    }

    // 日表示の間は、先頭に見えている行を覚えておく。
    LaunchedEffect(state.showCalendar, journalListState) {
        if (!state.showCalendar) return@LaunchedEffect
        listEnteredFromDay = true
        snapshotFlow {
            JournalDayViewAnchor(
                date = state.selectedDate,
                eventId = (journalListState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String)
                    ?.removePrefix(JournalNoteKeyPrefix),
                offset = journalListState.firstVisibleItemScrollOffset,
            )
        }.collect { dayViewAnchor = it }
    }

    // 月表示は次のどちらかから見せる。戻る操作で作り直したときは、どちらもせず位置を保つ。
    // - 日表示から切り替えたとき: 日表示で先頭に見えていた行（記録のない日なら、その日の位置）。
    // - 今月を開いたとき: 今日までで一番新しい行（ふつうは一覧の一番下）。
    //   取得が進んで行が増えても追いかけ、利用者がほかの行へスクロールしたらやめる。
    LaunchedEffect(state.showCalendar, state.selectedMonth, journalListState) {
        if (state.showCalendar) return@LaunchedEffect
        val month = state.selectedMonth.toString()
        if (listEnteredFromDay) {
            listEnteredFromDay = false
            todayScrolledMonth = month
            val anchor = dayViewAnchor ?: return@LaunchedEffect
            val index = state.dayAnchorIndex(anchor.eventId, anchor.date) ?: return@LaunchedEffect
            journalListState.scrollWhenLaidOut(index, if (anchor.eventId != null) anchor.offset else 0)
            return@LaunchedEffect
        }
        if (todayScrolledMonth == month) return@LaunchedEffect
        todayScrolledMonth = month
        pendingTodayScroll[0] = true
        // 最後に自分で合わせた位置。ここから動いていれば利用者がスクロールしたとみなす。
        // 今日が一覧の末尾近くだと先頭まで上げられず、上に行が増えるたびに末尾へ寄せ直される。
        // そのため、合わせたときに末尾にいて今も末尾にいるなら、動かしていないとみなす。
        var anchored: Pair<Any?, Int>? = null
        var anchoredAtEnd = false
        fun currentPosition(): Pair<Any?, Int> =
            journalListState.layoutInfo.visibleItemsInfo.firstOrNull()?.key to
                journalListState.firstVisibleItemScrollOffset
        fun isUntouched(): Boolean =
            anchored == null ||
                anchored == currentPosition() ||
                (anchoredAtEnd && !journalListState.canScrollForward)
        snapshotFlow { state.todayScrollIndex() }
            .filterNotNull()
            .distinctUntilChanged()
            .takeWhile { isUntouched() }
            .collect { index ->
                // 一覧がまだ配置されていなければ位置を比べられないため、次の更新でも合わせ直す。
                val laidOut = journalListState.scrollWhenLaidOut(index)
                anchored = currentPosition().takeIf { laidOut && it.first != null }
                anchoredAtEnd = anchored != null && !journalListState.canScrollForward
            }
    }

    // 一覧が消えると引っ張りで広げられないため、空の日は月全体を見せる。
    LaunchedEffect(visibleEntries.isEmpty()) {
        if (visibleEntries.isEmpty()) calendarCollapsed = false
    }

    LaunchedEffect(state.engagementError) {
        val error = state.engagementError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.consumeEngagementError()
    }

    LaunchedEffect(viewModel, toggleCalendarRequest) {
        viewModel.applyCalendarToggleRequest(toggleCalendarRequest)
    }

    LaunchedEffect(relays, selectedRelayUrl) {
        if (relays.isEmpty()) return@LaunchedEffect
        val active = selectedRelayUrl?.takeIf { it in relays } ?: relays.firstOrNull()
        delay(JournalInitialLoadDelayMs)
        viewModel.setRelayUrl(active)
    }

    LaunchedEffect(explicitlySelectedKinds) {
        viewModel.setKinds(explicitlySelectedKinds)
    }

    val headerContentColor = MaterialTheme.colorScheme.onBackground

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (showCalendarToggleFab) {
                // アイコンは切り替え先を表す。
                AppFloatingActionButton(
                    onClick = viewModel::toggleCalendar,
                    icon = if (state.showCalendar) Icons.AutoMirrored.Filled.ViewList else Icons.Default.CalendarMonth,
                    contentDescription = if (state.showCalendar) "月の一覧に切り替える" else "日ごとの表示に切り替える",
                )
            }
        },
        topBar = {
            Column {
            AppTopBar(
                title = {
                    RelaySelector(
                        relays = relays,
                        selectedRelayUrl = selectedRelayUrl,
                        onRelaySelected = RelayStore::setSelectedMemoRelayUrl,
                        onOpenRelaySettings = onOpenRelaySettings,
                    )
                },
                navigationIcon = {
                    if (isUserJournal) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "戻る",
                                tint = headerContentColor,
                            )
                        }
                    } else if (ownPubkey != null) {
                        IconButton(onClick = { onUserClick(ownPubkey) }) {
                            AvatarCircle(
                                pubkey = ownPubkey,
                                name = ownProfile?.bestName,
                                pictureUrl = ownProfile?.picture,
                                size = 34,
                            )
                        }
                    } else {
                        Box(modifier = Modifier.size(48.dp))
                    }
                },
                actions = {
                    com.nostr.torinos.ui.article.MatometrStartButton()
                    IconButton(onClick = { showFilterHeader = !showFilterHeader }) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = if (showFilterHeader) "フィルターを閉じる" else "フィルターを開く",
                            tint = if (showFilterHeader) MaterialTheme.colorScheme.primary else headerContentColor,
                        )
                    }
                },
            )
            com.nostr.torinos.ui.article.MatometrSelectionBar()
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .journalHorizontalSwipe(
                    canGoNext = if (state.showCalendar) state.canGoNextDate else state.canGoNextMonth,
                    onPrevious = if (state.showCalendar) viewModel::previousDate else viewModel::previousMonth,
                    onNext = if (state.showCalendar) viewModel::nextDate else viewModel::nextMonth,
                ),
        ) {
            JournalCalendarHeader(
                state = state,
                onPreviousMonth = viewModel::previousMonth,
                onNextMonth = viewModel::nextMonth,
                onToggleCalendar = viewModel::toggleCalendar,
            )
            if (state.showCalendar) {
                JournalCalendarGrid(
                    state = state,
                    collapsed = calendarCollapsed,
                    onSelectDate = viewModel::selectDate,
                    onExpand = { calendarCollapsed = false },
                )
            }
            if (showFilterHeader) {
                JournalFilterHeader(
                    kinds = JournalActivityKind.entries.filter { it in availableKinds },
                    selectedKinds = explicitlySelectedKinds,
                    defaultKinds = defaultJournalKinds(),
                    counts = state.kindCounts,
                    onToggle = { kind ->
                        val nextKinds = if (kind in explicitlySelectedKinds) {
                            explicitlySelectedKinds - kind
                        } else {
                            explicitlySelectedKinds + kind
                        }
                        selectedKindNames = nextKinds.sortedBy { it.ordinal }.map { it.name }
                    },
                )
                // 先頭（自分、または開いた人）は外せない。他人のジャーナルで足した人は保存しない。
                JournalMemberBar(
                    members = state.members,
                    selectedMembers = state.selectedMembers,
                    profiles = state.profiles,
                    removablePubkey = { it != state.members.firstOrNull() },
                    canAdd = state.canAddMember,
                    onToggle = viewModel::toggleMemberSelection,
                    onRemove = viewModel::removeMember,
                    onAdd = { showMemberSheet = true },
                )
            }
            HorizontalDivider()
            val scope = rememberCoroutineScope()
            JournalSelectionHeading(
                state = state,
                isPullRefreshing = isPullRefreshing,
                canScrollUp = journalListState.canScrollBackward,
                canScrollDown = journalListState.canScrollForward,
                onGoToToday = viewModel::goToToday,
                onScrollToTop = { scope.launch { journalListState.scrollToItem(0) } },
                onScrollToBottom = {
                    scope.launch { journalListState.scrollToItem(visibleEntries.lastIndex.coerceAtLeast(0)) }
                },
            )
            val journalContent: @Composable BoxScope.() -> Unit = {
                when (
                    journalListContent(
                        isLoading = state.isLoading,
                        isBackfilling = state.isBackfilling,
                        showCalendar = state.showCalendar,
                        hasEntries = visibleEntries.isNotEmpty(),
                        hasError = state.error != null,
                        isPullRefreshing = isPullRefreshing,
                    )
                ) {
                    // 行は投稿カードと同じなので、フィードと同じスケルトンにする。
                    JournalListContent.Loading -> NoteSkeletonList(
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.fillMaxSize(),
                    )
                    JournalListContent.Error -> JournalMessage(
                        text = state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                    JournalListContent.Empty -> JournalMessage(
                        text = when {
                            JournalActivityKind.ReceivedLike in state.kinds -> "被いいねされた投稿はありません"
                            state.showCalendar -> "この日の投稿はありません"
                            else -> "この月の投稿はありません"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    JournalListContent.Entries -> {
                        if (pendingTodayScroll[0] && !state.showCalendar) {
                            state.todayScrollIndex()?.let { index ->
                                journalListState.requestScrollToItem(index)
                                pendingTodayScroll[0] = false
                            }
                        }
                        LazyColumn(
                            state = journalListState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = visibleEntries,
                                key = { entry -> JournalNoteKeyPrefix + entry.event.id },
                                contentType = { "note" },
                            ) { entry ->
                                JournalEntryRow(
                                    entry = entry,
                                    state = state,
                                    viewModel = viewModel,
                                    ownPubkey = ownPubkey,
                                    onOpenThread = onOpenThread,
                                    onReply = onReply,
                                    onUserClick = onUserClick,
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
            // ブラウザではプルで更新が動かないため、Web 版では付けない。
            // 折りたたみはプルで更新より外側で受け、畳まれている間の引っ張りは展開に使う。
            val contentModifier = Modifier
                .fillMaxSize()
                .let { if (state.showCalendar) it.nestedScroll(calendarCollapseConnection) else it }
            if (isWebPlatform) {
                Box(modifier = contentModifier, content = journalContent)
            } else {
                PullToRefreshBox(
                    isRefreshing = isPullRefreshing,
                    onRefresh = {
                        if (!state.isLoading) {
                            pullRefreshRequested = true
                            viewModel.refresh()
                        }
                    },
                    modifier = contentModifier,
                    content = journalContent,
                )
            }
        }
    }

    if (showMemberSheet) {
        JournalMemberSheet(
            candidates = state.memberCandidates,
            members = state.members,
            profiles = state.profiles,
            rankingLabel = if (isUserJournal) "この人と最近30日のやりとりが多い順" else "最近30日のやりとりが多い順",
            onLoadCandidates = viewModel::loadMemberCandidates,
            onAdd = { pubkey ->
                viewModel.addMember(pubkey)
                showMemberSheet = false
            },
            onDismiss = { showMemberSheet = false },
        )
    }

    state.noteDeleteDialog?.let { dialog ->
        DeleteNoteDialog(
            isDeleting = dialog.isDeleting,
            error = dialog.error,
            preview = dialog.event.content,
            onDismiss = viewModel::dismissNoteDeleteDialog,
            onConfirm = viewModel::deleteSelectedNote,
        )
    }
}

@Composable
private fun JournalEntryRow(
    entry: JournalActivity,
    state: JournalState,
    viewModel: JournalViewModel,
    ownPubkey: String?,
    onOpenThread: (String) -> Unit,
    onReply: ((event: NostrEvent, preview: String) -> Unit)?,
    onUserClick: (String) -> Unit,
) {
    val event = entry.event
    CompositionLocalProvider(LocalMatometrSelectionEnabled provides true) {
        when (event.kind) {
            1, COMMENT_EVENT_KIND -> {
                val engagement = state.engagementOf(event.id)
                val summary = engagement.summary
                val isLiked = summary.ownLikeEventId != null ||
                    summary.pendingOperations[EngagementSlot.Reaction]?.request is EngagementRequest.AddLike
                val replyParentId = event.replyTargetId()
                NoteCardHost(
                    model = NoteCardModel(
                        event = event,
                        profiles = state.profiles,
                        author = NoteAuthorPresentation(
                            profile = state.profiles[event.pubkey],
                            ownPubkey = ownPubkey,
                        ),
                        engagement = NoteEngagementPresentation(
                            replyCount = engagement.replyCount,
                            replies = engagement.replies,
                            reactionCount = summary.reactionCount,
                            likeReactionCount = summary.likeReactionCount,
                            customReactions = summary.customReactions,
                            unicodeReactions = summary.unicodeReactions,
                            reactionEvents = engagement.reactionEvents,
                            repostCount = summary.repostCount,
                            repostPubkeys = engagement.repostPubkeys,
                            isLiked = isLiked,
                            ownEmojiReactionEventIds = summary.displayOwnEmojiReactionEventIds,
                        ),
                        references = NoteReferencePresentation(
                            replyParent = replyParentId
                            ?.let { state.referencedEvents[it] }
                            ?.let { QuotedEvent(event = it, profile = state.profiles[it.pubkey]) },
                            quotedEvents = quotedEventIds(event)
                            .filter { it != replyParentId }
                            .mapNotNull { id ->
                                state.referencedEvents[id]?.let { QuotedEvent(event = it, profile = state.profiles[it.pubkey]) }
                            },
                        ),
                        policy = NoteCardPolicy(
                        ),
                    ),
                    actions = NoteCardActions(
                        onUserClick = onUserClick,
                        onDelete = if (event.pubkey == ownPubkey) {
                            { viewModel.showNoteDeleteDialog(event) }
                        } else null,
                        onLike = if (ownPubkey != null) {
                            {
                                if (isLiked) viewModel.unreact(event.id) else viewModel.react(event.id, event.pubkey)
                            }
                        } else null,
                        onEmojiReact = if (ownPubkey != null) {
                            { option -> viewModel.reactWithEmoji(event.id, event.pubkey, option) }
                        } else null,
                        onEmojiUnreact = if (ownPubkey != null) {
                            { option -> viewModel.unreactWithEmoji(event.id, option) }
                        } else null,
                        onReply = if (ownPubkey != null && onReply != null) {
                            { onReply(event, event.content.take(100)) }
                        } else null,
                        onOpenReplies = { onOpenThread(event.id) },
                        onNoteClick = { onOpenThread(event.id) },
                    ),
                )
            }
            6, 16, 7 -> {
                val target = event.activityTargetId()?.let { state.referencedEvents[it] }
                JournalActivityRow(
                    event = event,
                    profile = state.profiles[event.pubkey],
                    targetEvent = target ?: event.embeddedRepostTarget(),
                    targetProfile = target?.let { state.profiles[it.pubkey] },
                    onUserClick = onUserClick,
                    onOpenThread = onOpenThread,
                )
            }
            else -> Unit
        }
    }
}

/** 一覧が何日（月一覧では何月）の記録かと件数。今日以外を見ているときは今日へ戻るボタンを出す。 */
@Composable
private fun JournalSelectionHeading(
    state: JournalState,
    isPullRefreshing: Boolean,
    canScrollUp: Boolean,
    canScrollDown: Boolean,
    onGoToToday: () -> Unit,
    onScrollToTop: () -> Unit,
    onScrollToBottom: () -> Unit,
) {
    // 月一覧では真上に月の見出しがあるため、ここでは繰り返さない。
    val title = if (state.showCalendar) journalDayTitle(state.selectedDate) else "この月"
    val countText = state.visibleEntries.size
        .takeIf {
            journalListContent(
                state.isLoading,
                state.isBackfilling,
                state.showCalendar,
                it > 0,
                state.error != null,
                isPullRefreshing,
            ) in
                setOf(JournalListContent.Entries, JournalListContent.Empty)
        }
        ?.let { "・${it}件" }
        .orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title + countText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 月表示は今日へ合わせて開くうえ、月の移動は見出しの矢印でできるため「今日」は日表示だけに出す。
        if (state.showCalendar && !state.isShowingToday) {
            TextButton(onClick = onGoToToday) {
                Text("今日")
            }
        }
        // 1画面に収まるときは出さない。端にいる側は押しても動かないため薄くする。
        if (canScrollUp || canScrollDown) {
            JournalJumpButton(
                icon = Icons.Default.ArrowUpward,
                contentDescription = "一番上へ",
                enabled = canScrollUp,
                onClick = onScrollToTop,
            )
            JournalJumpButton(
                icon = Icons.Default.ArrowDownward,
                contentDescription = "一番下へ",
                enabled = canScrollDown,
                onClick = onScrollToBottom,
            )
        }
    }
}

@Composable
private fun JournalJumpButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

internal fun journalDayTitle(date: LocalDate): String =
    "${date.month.ordinal + 1}月${date.day}日（${JournalWeekdayLabels[date.dayOfWeek.ordinal]}）"

/** `DayOfWeek`の宣言順（月曜始まり）に合わせた曜日名。 */
private val JournalWeekdayLabels = listOf("月", "火", "水", "木", "金", "土", "日")

/**
 * 日表示のカレンダーを一覧のスクロールで畳む。
 * スクロール位置からではなく指の動きで決める。位置から決めると、畳んで一覧が広がった拍子に位置が0へ戻り開閉が揺れるため。
 */
private class JournalCalendarCollapseConnection(
    private val listState: LazyListState,
    private val isCollapsed: () -> Boolean,
    private val setCollapsed: (Boolean) -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val collapsed = isCollapsed()
        when {
            available.y < 0f && !collapsed && listState.canScrollForward -> setCollapsed(true)
            available.y > 0f && collapsed && !listState.canScrollBackward -> {
                setCollapsed(false)
                // 同じ引っ張りでプルで更新まで始めないよう、この移動はここで使い切る。
                return Offset(0f, available.y)
            }
        }
        return Offset.Zero
    }

    // ホイールや慣性は先頭で止まると引っ張りが届かないため、先頭まで戻った時点で広げる。
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y > 0f && isCollapsed() && !listState.canScrollBackward) setCollapsed(false)
        return Offset.Zero
    }
}

@Composable
private fun JournalMessage(text: String, color: Color) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(contentType = "message") {
            Box(
                modifier = Modifier
                    .fillParentMaxSize()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    color = color,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun Modifier.journalHorizontalSwipe(
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier = pointerInput(canGoNext, onPrevious, onNext) {
    var dragAmount = 0f
    detectHorizontalDragGestures(
        onDragStart = { dragAmount = 0f },
        onHorizontalDrag = { change, amount ->
            dragAmount += amount
            change.consume()
        },
        onDragEnd = {
            when {
                dragAmount < -SwipeThresholdPx && canGoNext -> onNext()
                dragAmount > SwipeThresholdPx -> onPrevious()
            }
            dragAmount = 0f
        },
        onDragCancel = { dragAmount = 0f },
    )
}

private const val SwipeThresholdPx = 80f
private const val JournalInitialLoadDelayMs = 200L
private const val JournalVisibleEngagementDelayMs = 150L
private const val JournalEngagementPrefetchItems = 6
private const val JournalLayoutWaitMs = 1_000L
private const val JournalNoteKeyPrefix = "note-"

/** 日表示で先頭に見えていた行。記録のない日なら[eventId]は null。 */
private data class JournalDayViewAnchor(val date: LocalDate, val eventId: String?, val offset: Int)

/**
 * 一覧が[index]の行を配置し終えてから移り、移った位置が配置に反映されるまで待つ。反映されたら true。
 * 行が増えた直後は一覧がまだ前の行数で配置されており、そのまま移ると前の末尾で止まる。
 * また移った直後の layoutInfo は前の位置のままなので、位置を読むなら反映を待つ必要がある。
 */
private suspend fun LazyListState.scrollWhenLaidOut(index: Int, offset: Int = 0): Boolean {
    withTimeoutOrNull(JournalLayoutWaitMs) {
        snapshotFlow { layoutInfo.totalItemsCount }.first { it > index }
    }
    scrollToItem(index, offset)
    return withTimeoutOrNull(JournalLayoutWaitMs) {
        snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull()?.index == firstVisibleItemIndex }.first { it }
    } != null
}

/** 一覧の欄に出すもの。 */
internal enum class JournalListContent { Loading, Error, Empty, Entries }

/** 一覧の欄が読み込み中か。月一覧は月の残りの取得（参照先・名前を含む）も待つ。 */
internal fun isJournalListLoading(isLoading: Boolean, isBackfilling: Boolean, showCalendar: Boolean): Boolean =
    isLoading || (!showCalendar && isBackfilling)

/**
 * 月一覧は月の残りの取得（参照先・名前を含む）が終わってから1回で出す。先に届いた今日の分だけを出すと、
 * 後から行が増えて一覧が跳ぶため。日表示はその日の取得が終われば出す。
 * 引っ張って更新している間は、出している一覧をそのまま残す。
 */
internal fun journalListContent(
    isLoading: Boolean,
    isBackfilling: Boolean,
    showCalendar: Boolean,
    hasEntries: Boolean,
    hasError: Boolean,
    isPullRefreshing: Boolean,
): JournalListContent = when {
    isPullRefreshing && hasEntries -> JournalListContent.Entries
    isJournalListLoading(isLoading, isBackfilling, showCalendar) -> JournalListContent.Loading
    hasError -> JournalListContent.Error
    hasEntries -> JournalListContent.Entries
    else -> JournalListContent.Empty
}
