package com.nostr.torinos.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NoteCard
import com.nostr.torinos.ui.components.QuotedEvent
import com.nostr.torinos.ui.components.formatTimestamp
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DiaryScreen(
    onBack: () -> Unit,
    onOpenMemo: (PostMemoData) -> Unit,
    onNewPost: () -> Unit,
    refreshTodayRequest: Int,
    onOpenThread: (eventId: String) -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onUserClick: (pubkey: String) -> Unit = {},
    ownPubkey: String? = null,
    viewModel: DiaryViewModel = viewModel(key = "diary") { DiaryViewModel() },
) {
    val state by viewModel.state.collectAsState()
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedMemoRelayUrl.collectAsState()
    var showCalendar by remember { mutableStateOf(true) }
    var showRelayMenu by remember { mutableStateOf(false) }
    val visibleEntries = if (showCalendar) state.selectedEntries else state.monthEntries

    LaunchedEffect(refreshTodayRequest) {
        if (refreshTodayRequest > 0) viewModel.refreshToday()
    }

    LaunchedEffect(relays, selectedRelayUrl) {
        val active = selectedRelayUrl?.takeIf { it in relays } ?: relays.firstOrNull()
        viewModel.setRelayUrl(active)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(onClick = onNewPost) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "ポスト",
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Text(
                            text = selectedRelayUrl?.relayDisplayName() ?: "—",
                            modifier = Modifier.weight(1f, fill = false),
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { showRelayMenu = true }) {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "リレー切り替え",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        DropdownMenu(
                            expanded = showRelayMenu,
                            onDismissRequest = { showRelayMenu = false },
                        ) {
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
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCalendar = !showCalendar }) {
                        Icon(
                            Icons.Default.Today,
                            contentDescription = if (showCalendar) "カレンダーを閉じる" else "カレンダーを開く",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "再読み込み",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            MemoCalendarHeader(
                state = state,
                onPreviousMonth = viewModel::previousMonth,
                onNextMonth = viewModel::nextMonth,
            )
            if (showCalendar) {
                MemoCalendarGrid(
                    state = state,
                    onSelectDate = viewModel::selectDate,
                )
            }
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading && state.memos.isEmpty() && state.notes.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.error != null -> {
                        Text(
                            text = state.error.orEmpty(),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 32.dp),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    visibleEntries.isEmpty() -> {
                        Text(
                            text = if (showCalendar) "この日の投稿はありません" else "この月の投稿はありません",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = visibleEntries,
                                key = { entry ->
                                    when (entry) {
                                        is DiaryEntry.Memo -> "memo-${entry.item.eventId}"
                                        is DiaryEntry.Note -> "note-${entry.event.id}"
                                    }
                                },
                                contentType = { entry ->
                                    when (entry) {
                                        is DiaryEntry.Memo -> "memo"
                                        is DiaryEntry.Note -> "note"
                                    }
                                },
                            ) { entry ->
                                when (entry) {
                                    is DiaryEntry.Memo -> MemoRow(
                                        item = entry.item,
                                        replyToProfile = entry.item.memo.replyToPubkey?.let { state.profiles[it] },
                                        onClick = { onOpenMemo(entry.item.memo) },
                                        onLongClick = { viewModel.showDeleteDialog(entry.item) },
                                    )
                                    is DiaryEntry.Note -> NoteCard(
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
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }

                if (state.isLoading && (state.memos.isNotEmpty() || state.notes.isNotEmpty())) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .size(24.dp),
                        strokeWidth = 2.dp,
                    )
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

@Composable
private fun MemoCalendarHeader(
    state: DiaryState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
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
    state: DiaryState,
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
                            memoCount = state.entryCountsByDate[date] ?: 0,
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
    memoCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    val backgroundColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        memoCount == 0 -> MaterialTheme.colorScheme.surface
        memoCount == 1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        memoCount == 2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    }
    val borderColor = if (date == currentDate()) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
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
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MemoRow(
    item: DiaryItem,
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
            imageVector = Icons.Default.Edit,
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
