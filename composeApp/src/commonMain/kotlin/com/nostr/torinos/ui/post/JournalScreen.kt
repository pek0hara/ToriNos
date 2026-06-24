package com.nostr.torinos.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.nostr.torinos.ui.components.AppTopBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.markdownPreview
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.model.toArticleMeta
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.NoteCard
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.QuotedEvent
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.stripImageUrls
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.profile.customEmojiMap
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun JournalScreen(
    onBack: () -> Unit,
    onOpenMemo: (PostMemoData, () -> Unit) -> Unit,
    onNewPost: () -> Unit,
    refreshTodayRequest: Int,
    toggleCalendarRequest: Int = 0,
    showCalendarRequest: Int = 0,
    onOpenThread: (eventId: String) -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onUserClick: (pubkey: String) -> Unit = {},
    onOpenArticle: (pubkey: String, identifier: String) -> Unit = { _, _ -> },
    ownPubkey: String? = null,
    accountKey: String? = ownPubkey,
    ownProfile: NostrProfile? = null,
    onOpenRelaySettings: () -> Unit = {},
    targetPubkey: String? = null,
    viewModel: JournalViewModel = viewModel(
        key = targetPubkey?.let { "journal-$it" } ?: "journal-${accountKey ?: "anonymous"}",
    ) {
        JournalViewModel(targetPubkey)
    },
) {
    val state by viewModel.state.collectAsState()
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedMemoRelayUrl.collectAsState()
    var showRelayMenu by remember { mutableStateOf(false) }
    var showFilterHeader by rememberSaveable(accountKey, targetPubkey) { mutableStateOf(true) }
    var selectedFilterNames by rememberSaveable(accountKey, targetPubkey) {
        mutableStateOf(defaultJournalEntryFilters().map { it.name })
    }
    val isUserJournal = targetPubkey != null
    val availableFilters = remember(isUserJournal) {
        JournalEntryFilter.entries.filter {
            it != JournalEntryFilter.Article &&
                (!isUserJournal || it !in setOf(JournalEntryFilter.Like, JournalEntryFilter.Memo))
        }
    }
    val selectedFilters = remember(selectedFilterNames, availableFilters) {
        selectedFilterNames
            .mapNotNull { name -> JournalEntryFilter.entries.firstOrNull { it.name == name } }
            .filter { it in availableFilters }
            .toSet()
            .ifEmpty { defaultJournalEntryFilters() }
    }
    val baseEntries = if (state.showCalendar) state.selectedEntries else state.monthEntries
    val visibleEntries = remember(baseEntries, selectedFilters) {
        if (selectedFilters.isEmpty()) baseEntries else baseEntries.filter { it.filter in selectedFilters }
    }
    val filteredEntryCountsByDate = remember(state.monthEntries, selectedFilters) {
        filteredEntryCountsByDate(state, selectedFilters)
    }
    val isPullRefreshing = state.isLoading && (state.memos.isNotEmpty() || state.notes.isNotEmpty())

    LaunchedEffect(refreshTodayRequest) {
        if (refreshTodayRequest > 0) viewModel.refreshToday()
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

    LaunchedEffect(selectedFilters) {
        viewModel.setLoadKinds(selectedFilters.mapTo(mutableSetOf()) { it.loadKind })
    }

    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            if (!isUserJournal) FloatingActionButton(onClick = onNewPost) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "ポスト",
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        topBar = {
            AppTopBar(
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
                                        RelayStore.setSelectedMemoRelayUrl(url)
                                        showRelayMenu = false
                                    },
                                    trailingIcon = if (url == selectedRelayUrl) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                                )
                            }
                        }
                    }
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
            MemoCalendarHeader(
                state = state,
                onPreviousMonth = viewModel::previousMonth,
                onNextMonth = viewModel::nextMonth,
                onToggleCalendar = viewModel::toggleCalendar,
            )
            if (state.showCalendar) {
                MemoCalendarGrid(
                    state = state,
                    entryCountsByDate = filteredEntryCountsByDate,
                    onSelectDate = viewModel::selectDate,
                )
            }
            if (showFilterHeader) {
                JournalFilterHeader(
                    filters = availableFilters,
                    selectedFilters = selectedFilters,
                    onToggle = { filter ->
                        val nextFilters = if (filter in selectedFilters) {
                            selectedFilters - filter
                        } else {
                            selectedFilters + filter
                        }
                        selectedFilterNames = nextFilters
                            .ifEmpty { defaultJournalEntryFilters() }
                            .sortedBy { JournalEntryFilter.entries.indexOf(it) }
                            .map { it.name }
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
                    state.isLoading && state.memos.isEmpty() && state.notes.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.error != null -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item(contentType = "message") {
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxSize()
                                        .padding(horizontal = 32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = state.error.orEmpty(),
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                    visibleEntries.isEmpty() -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item(contentType = "message") {
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxSize()
                                        .padding(horizontal = 32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = if (state.showCalendar) "この日の投稿はありません" else "この月の投稿はありません",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = visibleEntries,
                                key = { entry ->
                                    when (entry) {
                                        is JournalEntry.Memo -> "memo-${entry.item.eventId}"
                                        is JournalEntry.Note -> "note-${entry.event.id}"
                                    }
                                },
                                contentType = { entry ->
                                    when (entry) {
                                        is JournalEntry.Memo -> "memo"
                                        is JournalEntry.Note -> "note"
                                    }
                                },
                            ) { entry ->
                                when (entry) {
                                    is JournalEntry.Memo -> MemoRow(
                                        item = entry.item,
                                        replyToProfile = entry.item.memo.replyToPubkey?.let { state.profiles[it] },
                                        onClick = { onOpenMemo(entry.item.memo) { viewModel.showDeleteDialog(entry.item) } },
                                        onLongClick = { viewModel.showDeleteDialog(entry.item) },
                                    )
                                    is JournalEntry.Note -> when (entry.event.kind) {
                                        1 -> NoteCard(
                                            event = entry.event,
                                            profile = entry.profile,
                                            profiles = state.profiles,
                                            replyParent = run {
                                                val parentId = entry.event.replyTargetId() ?: return@run null
                                                val parentEvent = state.quotedEvents[parentId] ?: return@run null
                                                QuotedEvent(event = parentEvent, profile = state.profiles[parentEvent.pubkey])
                                            },
                                            quotedEvents = run {
                                                val replyParentId = entry.event.replyTargetId()
                                                quotedEventIds(entry.event)
                                                    .filter { it != replyParentId }
                                                    .mapNotNull { id ->
                                                        state.quotedEvents[id]?.let { ev ->
                                                            QuotedEvent(event = ev, profile = state.profiles[ev.pubkey])
                                                        }
                                                    }
                                            },
                                            replyCount = state.replyCounts[entry.event.id] ?: 0,
                                            reactionCount = state.reactionCounts[entry.event.id] ?: 0,
                                            repostCount = state.repostCounts[entry.event.id] ?: 0,
                                            isLiked = state.likedReactions.containsKey(entry.event.id),
                                            onUserClick = onUserClick,
                                            onLike = if (ownPubkey != null) {
                                                {
                                                    if (state.likedReactions.containsKey(entry.event.id)) {
                                                        viewModel.unreact(entry.event.id)
                                                    } else {
                                                        viewModel.react(entry.event.id, entry.event.pubkey)
                                                    }
                                                }
                                            } else null,
                                            onReply = if (ownPubkey != null && onReply != null) {
                                                {
                                                    onReply(
                                                        entry.event.id,
                                                        entry.event.pubkey,
                                                        entry.event.content.take(100),
                                                    )
                                                }
                                            } else null,
                                            onOpenReplies = { onOpenThread(entry.event.id) },
                                            onNoteClick = { onOpenThread(entry.event.id) },
                                        )
                                        6, 7 -> JournalActivityRow(
                                            event = entry.event,
                                            profile = entry.profile,
                                            targetEvent = entry.event.activityTargetId()?.let { state.quotedEvents[it] }
                                                ?: entry.event.embeddedRepostTarget(),
                                            targetProfile = entry.event.activityTargetId()
                                                ?.let { state.quotedEvents[it] }
                                                ?.let { state.profiles[it.pubkey] },
                                            onUserClick = onUserClick,
                                            onOpenThread = { targetId ->
                                                onOpenThread(targetId)
                                            },
                                        )
                                        NIP23_ARTICLE_KIND -> JournalArticleRow(
                                            event = entry.event,
                                            profile = entry.profile,
                                            onUserClick = onUserClick,
                                            onOpenArticle = onOpenArticle,
                                        )
                                        else -> Unit
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    state.deleteDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteDialog,
            title = { Text("ポストメモを削除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("このポストメモの削除要求をリレーへ送信します。対応していないリレーやキャッシュ済みデータからの削除は保証されません。")
                    Text(
                        text = memoPreview(dialog.item.memo),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    dialog.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::deleteSelectedMemo,
                    enabled = !dialog.isDeleting,
                ) {
                    if (dialog.isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissDeleteDialog,
                    enabled = !dialog.isDeleting,
                ) {
                    Text("キャンセル")
                }
            },
        )
    }

}

private enum class JournalEntryFilter(val label: String, val icon: ImageVector) {
    Post("投稿", Icons.Default.PostAdd),
    Reply("返信", Icons.Default.MailOutline),
    Repost("リポスト", Icons.Default.Repeat),
    Like("いいね", Icons.Default.Favorite),
    Memo("メモ", Icons.Default.Edit),
    Article("記事", Icons.AutoMirrored.Filled.Article),
}

private val JournalEntryFilter.loadKind: JournalLoadKind
    get() = when (this) {
        JournalEntryFilter.Post -> JournalLoadKind.Post
        JournalEntryFilter.Reply -> JournalLoadKind.Reply
        JournalEntryFilter.Repost -> JournalLoadKind.Repost
        JournalEntryFilter.Like -> JournalLoadKind.Like
        JournalEntryFilter.Memo -> JournalLoadKind.Memo
        JournalEntryFilter.Article -> JournalLoadKind.Article
    }

private fun defaultJournalEntryFilters(): Set<JournalEntryFilter> =
    setOf(JournalEntryFilter.Post)

private val JournalEntry.filter: JournalEntryFilter
    get() = when (this) {
        is JournalEntry.Memo -> JournalEntryFilter.Memo
        is JournalEntry.Note -> when (event.kind) {
            6 -> JournalEntryFilter.Repost
            7 -> JournalEntryFilter.Like
            NIP23_ARTICLE_KIND -> JournalEntryFilter.Article
            else -> if (event.replyTargetId() != null) JournalEntryFilter.Reply else JournalEntryFilter.Post
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

@Composable
private fun JournalFilterHeader(
    filters: List<JournalEntryFilter>,
    selectedFilters: Set<JournalEntryFilter>,
    onToggle: (JournalEntryFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters) { filter ->
            FilterChip(
                selected = filter in selectedFilters,
                onClick = { onToggle(filter) },
                label = {
                    Icon(
                        imageVector = filter.icon,
                        contentDescription = filter.label,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun MemoCalendarHeader(
    state: JournalState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToggleCalendar: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "前月",
            )
        }
        Text(
            text = "${state.selectedMonth.year}年${state.selectedMonth.month.ordinal + 1}月",
            modifier = Modifier.clickable(onClick = onToggleCalendar),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(
            onClick = onNextMonth,
            enabled = state.canGoNextMonth,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "翌月",
            )
        }
    }
}

@Composable
private fun MemoCalendarGrid(
    state: JournalState,
    entryCountsByDate: Map<LocalDate, Int>,
    onSelectDate: (LocalDate) -> Unit,
) {
    val weekRows = remember(state.selectedMonth) { calendarWeeks(state.selectedMonth) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("日", "月", "火", "水", "木", "金", "土").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }

        weekRows.forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                week.forEach { date ->
                    if (date == null) {
                        Box(modifier = Modifier.weight(1f).height(32.dp))
                    } else {
                        MemoCalendarDay(
                            date = date,
                            selected = date == state.selectedDate,
                            entryCount = entryCountsByDate[date] ?: 0,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                repeat(7 - week.size) {
                    Box(modifier = Modifier.weight(1f).height(32.dp))
                }
            }
        }
    }
}

private fun calendarWeeks(month: LocalDate): List<List<LocalDate?>> {
    val leadingBlankDays = (month.dayOfWeek.ordinal + 1) % 7
    val days = (1..daysInMonth(month.year, month.month.ordinal + 1)).map { day ->
        LocalDate(month.year, month.month, day)
    }
    val cells = List(leadingBlankDays) { null } + days
    return cells.chunked(7)
}

@Composable
private fun MemoCalendarDay(
    date: LocalDate,
    selected: Boolean,
    entryCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    val colorScheme = MaterialTheme.colorScheme
    val entryIntensity = calendarEntryIntensity(entryCount)
    val backgroundColor = when {
        selected -> colorScheme.primary
        entryCount == 0 -> colorScheme.surface
        else -> lerp(colorScheme.surface, colorScheme.primary, entryIntensity)
    }
    val borderColor = when {
        selected -> colorScheme.primary
        date == currentDate() -> colorScheme.primary
        else -> colorScheme.outlineVariant
    }
    val contentColor = when {
        selected -> colorScheme.onPrimary
        entryIntensity >= CalendarEntryHighContrastThreshold -> colorScheme.onPrimary
        else -> colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.day.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
    }
}

private fun calendarEntryIntensity(entryCount: Int): Float {
    if (entryCount <= 0) return 0f
    val clampedCount = entryCount.coerceIn(1, CalendarEntryMaxGradientCount)
    val step = (clampedCount - 1).toFloat() / (CalendarEntryMaxGradientCount - 1)
    return CalendarEntryMinIntensity + (CalendarEntryMaxIntensity - CalendarEntryMinIntensity) * step
}

@Composable
private fun MemoRow(
    item: JournalItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    replyToProfile: NostrProfile? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = JournalEntryFilter.Memo.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = formatTimestamp(item.displayTime),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = memoPreview(item.memo),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.memo.noteKind == 42 || item.memo.replyToId != null) {
                Text(
                    text = memoKindLabel(item.memo, replyToProfile),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun JournalActivityRow(
    event: NostrEvent,
    profile: NostrProfile?,
    targetEvent: NostrEvent?,
    targetProfile: NostrProfile?,
    onUserClick: (String) -> Unit,
    onOpenThread: (String) -> Unit,
) {
    val type = if (event.kind == 6) JournalEntryFilter.Repost else JournalEntryFilter.Like
    val targetId = event.activityTargetId()
    val icon = if (type == JournalEntryFilter.Repost) Icons.Default.Repeat else Icons.Default.Favorite
    val accent = if (type == JournalEntryFilter.Repost) Color(0xFF2BAE66) else Color(0xFFE17055)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = targetId != null) { targetId?.let(onOpenThread) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(
            pubkey = event.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 40,
            modifier = Modifier.clickable { onUserClick(event.pubkey) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JournalActivityIcon(icon = icon, tint = accent)
                    ProfileNameText(
                        profile = profile,
                        fallback = event.shortPubkey,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = "が${type.label}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = formatTimestamp(event.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LinkedText(
                text = targetPreviewText(targetEvent, targetProfile),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                customEmojis = targetEvent?.tags?.customEmojiMap().orEmpty(),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun JournalArticleRow(
    event: NostrEvent,
    profile: NostrProfile?,
    onUserClick: (String) -> Unit,
    onOpenArticle: (String, String) -> Unit,
) {
    val meta = event.toArticleMeta() ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenArticle(event.pubkey, meta.identifier) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AvatarCircle(
            pubkey = event.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 40,
            modifier = Modifier.clickable { onUserClick(event.pubkey) },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JournalActivityIcon(
                        icon = JournalEntryFilter.Article.icon,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    ProfileNameText(
                        profile = profile,
                        fallback = event.shortPubkey,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = formatTimestamp(meta.publishedAt ?: event.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = meta.title?.takeIf { it.isNotBlank() } ?: markdownPreview(event.content).ifBlank { "無題の記事" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta.summary?.takeIf { it.isNotBlank() } ?: markdownPreview(event.content),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun JournalActivityIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = tint,
    )
}

private fun memoPreview(memo: PostMemoData): String =
    memo.text.takeIf { it.isNotBlank() }
        ?: memo.imageUrls.takeIf { it.isNotEmpty() }?.let { "画像 ${it.size} 件" }
        ?: "空のメモ"

private fun memoKindLabel(memo: PostMemoData, replyToProfile: NostrProfile? = null): String =
    when {
        memo.noteKind == 42 -> "チャンネル"
        memo.replyToId != null -> {
            val name = replyToProfile?.bestName
                ?: memo.replyToPubkey?.let { it.take(8) + "…" + it.takeLast(4) }
            if (name != null) "返信先: $name" else "返信"
        }
        else -> "通常投稿"
    }

private fun filteredEntryCountsByDate(
    state: JournalState,
    selectedFilters: Set<JournalEntryFilter>,
): Map<LocalDate, Int> =
    state.monthEntries
        .asSequence()
        .filter { selectedFilters.isEmpty() || it.filter in selectedFilters }
        .groupingBy { dateOfEpochSeconds(it.displayTime) }
        .eachCount()

private fun NostrEvent.activityTargetId(): String? =
    tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
        ?: embeddedRepostTarget()?.id

private fun NostrEvent.embeddedRepostTarget(): NostrEvent? {
    if (kind != 6 || content.isBlank()) return null
    return runCatching {
        Json.decodeFromString(NostrEvent.serializer(), content)
    }.getOrNull()
}

private fun targetPreviewText(event: NostrEvent?, profile: NostrProfile?): String {
    if (event == null) return "対象ポストを読み込み中"
    val author = profile?.bestName ?: event.shortPubkey
    val body = event.content.previewText()
    return if (body.isBlank()) {
        "$author のポスト"
    } else {
        "$author: $body"
    }
}

private fun String.previewText(): String =
    stripImageUrls(stripNostrEventUris(this))
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(140)

private fun String.relayDisplayName(): String =
    removePrefix("wss://").removePrefix("ws://").trimEnd('/')

private fun daysInMonth(year: Int, month: Int): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 30
    }

private fun isLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private const val SwipeThresholdPx = 80f
private const val JournalInitialLoadDelayMs = 200L
private const val CalendarEntryMaxGradientCount = 10
private const val CalendarEntryMinIntensity = 0.16f
private const val CalendarEntryMaxIntensity = 0.82f
private const val CalendarEntryHighContrastThreshold = 0.62f
