package com.nostr.torinos.ui.post

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.accountSessionViewModel
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
import com.nostr.torinos.ui.components.AppFloatingActionButton
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.DeleteNoteDialog
import com.nostr.torinos.ui.components.NoteCard
import com.nostr.torinos.ui.components.QuotedEvent
import com.nostr.torinos.ui.components.RelaySelector
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    onBack: () -> Unit,
    onNewPost: () -> Unit,
    toggleCalendarRequest: Int = 0,
    showCalendarRequest: Int = 0,
    onOpenThread: (eventId: String) -> Unit = {},
    onReply: ((event: NostrEvent, preview: String) -> Unit)? = null,
    onUserClick: (pubkey: String) -> Unit = {},
    ownPubkey: String? = null,
    accountKey: String? = ownPubkey,
    ownProfile: NostrProfile? = null,
    onOpenRelaySettings: () -> Unit = {},
    targetPubkey: String? = null,
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
    val availableKinds = remember(isUserJournal) { availableJournalKinds(isSelf = !isUserJournal) }
    val explicitlySelectedKinds = remember(selectedKindNames, availableKinds) {
        journalKindsFromNames(selectedKindNames, availableKinds)
    }
    val visibleEntries = state.visibleEntries
    val journalListState = rememberLazyListState()
    val isPullRefreshing = state.isLoading && !state.timeline.isEmpty()

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

    LaunchedEffect(state.engagementError) {
        val error = state.engagementError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.consumeEngagementError()
    }

    LaunchedEffect(toggleCalendarRequest) {
        if (toggleCalendarRequest > 0) viewModel.toggleCalendar()
    }

    LaunchedEffect(showCalendarRequest) {
        if (showCalendarRequest > 0) viewModel.showCalendar()
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
            if (!isUserJournal) AppFloatingActionButton(
                onClick = onNewPost,
                icon = Icons.Default.Add,
                contentDescription = "ポスト",
            )
        },
        topBar = {
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
                    IconButton(onClick = { showFilterHeader = !showFilterHeader }) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = if (showFilterHeader) "フィルターを閉じる" else "フィルターを開く",
                            tint = headerContentColor,
                        )
                    }
                    IconButton(onClick = viewModel::toggleCalendar) {
                        Icon(
                            Icons.Default.Today,
                            contentDescription = if (state.showCalendar) "カレンダーを閉じる" else "カレンダーを開く",
                            tint = headerContentColor,
                        )
                    }
                },
            )
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
                    onSelectDate = viewModel::selectDate,
                )
            }
            if (showFilterHeader) {
                JournalFilterHeader(
                    kinds = JournalActivityKind.entries.filter { it in availableKinds },
                    selectedKinds = explicitlySelectedKinds,
                    defaultKinds = defaultJournalKinds(),
                    onToggle = { kind ->
                        val nextKinds = if (kind in explicitlySelectedKinds) {
                            explicitlySelectedKinds - kind
                        } else {
                            explicitlySelectedKinds + kind
                        }
                        selectedKindNames = nextKinds.sortedBy { it.ordinal }.map { it.name }
                    },
                )
            }
            HorizontalDivider()
            PullToRefreshBox(
                isRefreshing = isPullRefreshing,
                onRefresh = {
                    if (!state.isLoading) {
                        viewModel.refresh()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isLoading && state.timeline.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.isLoading && visibleEntries.isEmpty() -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "ジャーナルを読み込んでいます",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.error != null -> JournalMessage(
                        text = state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                    visibleEntries.isEmpty() -> JournalMessage(
                        text = when {
                            JournalActivityKind.ReceivedLike in state.kinds -> "被いいねされた投稿はありません"
                            state.showCalendar -> "この日の投稿はありません"
                            else -> "この月の投稿はありません"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(
                        state = journalListState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = visibleEntries,
                            key = { entry -> "note-${entry.event.id}" },
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
    when (event.kind) {
        1, COMMENT_EVENT_KIND -> {
            val engagement = state.engagementOf(event.id)
            val summary = engagement.summary
            val isLiked = summary.ownLikeEventId != null ||
                summary.pendingOperations[EngagementSlot.Reaction]?.request is EngagementRequest.AddLike
            val replyParentId = event.replyTargetId()
            NoteCard(
                event = event,
                profile = state.profiles[event.pubkey],
                profiles = state.profiles,
                replyParent = replyParentId
                    ?.let { state.referencedEvents[it] }
                    ?.let { QuotedEvent(event = it, profile = state.profiles[it.pubkey]) },
                quotedEvents = quotedEventIds(event)
                    .filter { it != replyParentId }
                    .mapNotNull { id ->
                        state.referencedEvents[id]?.let { QuotedEvent(event = it, profile = state.profiles[it.pubkey]) }
                    },
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
                onUserClick = onUserClick,
                ownPubkey = ownPubkey,
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
            )
        }
        6, 7 -> {
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
