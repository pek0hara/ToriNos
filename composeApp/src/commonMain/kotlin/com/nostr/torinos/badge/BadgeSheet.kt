package com.nostr.torinos.badge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val DisplayedRowHeight = 64.dp

/** Badge list for a profile: management (display list, received, issued) for the signed-in account, read-only otherwise. */
@Composable
internal fun BadgeListSheet(pubkey: String, onDismiss: () -> Unit, onUserClick: (String) -> Unit) {
    val session = LocalAccountSession.current?.takeIf { it.pubkey == pubkey }
    if (session != null) BadgeManageSheet(session, onDismiss, onUserClick) else BadgeViewSheet(pubkey, onDismiss, onUserClick)
}

// ---------------------------------------------------------------------------------------------
// Read-only sheet for someone else's profile
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BadgeViewSheet(pubkey: String, onDismiss: () -> Unit, onUserClick: (String) -> Unit) {
    val profile = rememberBadgeProfile(pubkey)
    val repository = LocalBadgeRepository.current
    var selected by remember(pubkey) { mutableStateOf<BadgeDisplayItem?>(null) }
    val issuers = rememberIssuerProfiles(profile.badges)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            val detail = selected
            if (detail != null) {
                BadgeSheetHeader("バッジの詳細", onBack = { selected = null })
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    BadgeDetailContent(detail, issuers[detail.issuer], onIssuerClick = { onDismiss(); onUserClick(it) })
                }
                return@Column
            }
            BadgeSheetHeader("バッジ", count = profile.badges.size.takeIf { it > 0 })
            BadgeSheetProgress(profile.loading)
            badgeSheetNotice(BadgeSheetTab.Displayed, profileError = profile.error, profilePartial = profile.partial && !profile.loading)?.let { notice ->
                BadgeNoticeBanner(notice) { action ->
                    if (action == BadgeNoticeAction.ResolveMore) repository?.resolveMore(pubkey) else repository?.request(pubkey, force = true)
                }
            }
            if (profile.badges.isEmpty()) {
                if (!profile.loading) BadgeEmptyState("表示しているバッジはありません")
                return@Column
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(76.dp),
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(profile.badges, key = { it.address }) { badge -> BadgeGridCell(badge) { selected = badge } }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BadgeGridCell(badge: BadgeDisplayItem, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BadgeTile(badge, 56)
        Text(badge.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------------
// Management sheet for the signed-in account
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BadgeManageSheet(session: AccountSession, onDismiss: () -> Unit, onUserClick: (String) -> Unit) {
    val pubkey = session.pubkey
    val store = session.badges
    val issuer = session.badgeIssuer
    val repository = LocalBadgeRepository.current
    val profile = rememberBadgeProfile(pubkey)
    val management by store.state.collectAsState()
    val issuerState by issuer.state.collectAsState()
    val scope = rememberCoroutineScope()

    var tab by remember(pubkey) { mutableStateOf(BadgeSheetTab.Displayed) }
    // null until the display list is changed; the saved list is shown meanwhile.
    var draft by remember(pubkey) { mutableStateOf<BadgeDraft?>(null) }
    var detail by remember(pubkey) { mutableStateOf<BadgeDisplayItem?>(null) }
    var issuerScreen by remember(pubkey) { mutableStateOf<IssuerScreen>(IssuerScreen.List) }
    var localError by remember(pubkey) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember(pubkey) { mutableStateOf(false) }
    var saving by remember(pubkey) { mutableStateOf(false) }

    fun reload() {
        localError = null
        repository?.request(pubkey, force = true)
        scope.launch {
            try { store.refreshSelection(); store.loadAwards() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { localError = e.message ?: "取得できませんでした" }
        }
    }
    LaunchedEffect(store) {
        try { store.refreshSelection(); store.loadAwards() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { localError = e.message ?: "取得できませんでした" }
    }

    val saved = remember(management.latest) { store.draft() }
    val working = draft ?: saved
    val dirty = draft?.isDirty() == true
    // save() publishes the new list before it returns, so the draft looks stale for a moment while saving.
    val draftStale = !saving && draft != null && draft?.baselineId != management.latest?.id
    // A conflict blocks the unsent list until a new one is saved, so saving is offered even without edits.
    val conflictOnly = !dirty && management.message == BADGE_MESSAGE_CONFLICT
    val canSave = dirty || conflictOnly
    val known = remember(profile.badges, management.awards) { (profile.badges + management.awards).associateBy { it.address } }
    val expanded = remember(working, repository) {
        working.source?.let { source -> repository?.expand(source.copy(tags = BadgeSelection.parse(source.tags).editedTags(working.items)), Int.MAX_VALUE) }
            ?: working.items
    }
    // Not tied to busy: loading received badges would otherwise lock the list for seconds after opening.
    val editable = management.ready
    fun shownState(address: String) = badgeShownState(working.items, expanded, address)
    fun toggle(badge: BadgeDisplayItem) {
        if (!management.ready) return
        draft = working.toggled(badge.address) { store.selectionItem(badge.address, badge.awardId) }
    }
    fun save() {
        val target = working
        scope.launch {
            localError = null
            saving = true
            try {
                if (store.save(target)) {
                    draft = null
                    if (confirmDiscard) onDismiss()
                }
            } finally { saving = false }
        }
    }

    // Only edits made here hold the sheet open; a stored conflict alone does not.
    val dirtyState = rememberUpdatedState(dirty)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { value ->
        // Swiping down or tapping outside with unsaved changes asks first instead of dropping them.
        if (value == SheetValue.Hidden && dirtyState.value) { confirmDiscard = true; false } else true
    })
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = {
            // Back press hides without consulting confirmValueChange; bring the sheet back to ask.
            if (dirtyState.value) { confirmDiscard = true; scope.launch { sheetState.show() } } else onDismiss()
        },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            val openDetail = detail
            val issuerTitle = if (tab == BadgeSheetTab.Issued && openDetail == null) issuerScreenTitle(issuerScreen, issuerState) else null
            when {
                openDetail != null -> BadgeSheetHeader("バッジの詳細", onBack = { detail = null })
                issuerTitle != null -> BadgeSheetHeader(issuerTitle, onBack = { issuerScreen = issuerScreenParent(issuerScreen) ?: IssuerScreen.List })
                else -> BadgeSheetHeader("バッジ", onRefresh = {
                    if (tab == BadgeSheetTab.Issued) scope.launch { issuer.refresh() } else reload()
                })
            }
            if (openDetail == null && issuerTitle == null) {
                BadgeSheetTabs(tab, onSelect = { tab = it }, counts = mapOf(
                    BadgeSheetTab.Displayed to working.items.size,
                    BadgeSheetTab.Received to management.awards.size,
                    BadgeSheetTab.Issued to issuerState.issued.size,
                ), marked = if (dirty) BadgeSheetTab.Displayed else null)
            }
            BadgeSheetProgress(if (tab == BadgeSheetTab.Issued && openDetail == null) issuerState.busy else profile.loading || management.busy)
            val notice = badgeSheetNotice(
                tab = if (openDetail != null) BadgeSheetTab.Displayed else tab,
                message = management.message, localError = localError, profileError = profile.error,
                profilePartial = profile.partial && !profile.loading, awardsIncomplete = management.incomplete,
                draftStale = draftStale, issuerMessage = issuerState.message, issuerIncomplete = issuerState.incomplete,
            )
            notice?.let {
                BadgeNoticeBanner(it) { action ->
                    when (action) {
                        BadgeNoticeAction.ReloadDraft -> draft = null
                        BadgeNoticeAction.ResolveMore -> repository?.resolveMore(pubkey)
                        BadgeNoticeAction.Retry -> if (tab == BadgeSheetTab.Issued) scope.launch { issuer.refresh() } else reload()
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    openDetail != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BadgeDetailContent(openDetail, rememberIssuerProfiles(listOf(openDetail))[openDetail.issuer],
                            onIssuerClick = { onDismiss(); onUserClick(it) })
                        BadgeDisplayToggle(shownState(openDetail.address), enabled = management.ready) { toggle(openDetail) }
                    }
                    tab == BadgeSheetTab.Displayed -> DisplayedTab(
                        draft = working, editable = editable, known = known, expanded = expanded, repository = repository, pubkey = pubkey,
                        syncLabel = badgeSyncLabel(management.message).takeIf { !dirty },
                        onChange = { draft = it }, onOpen = { detail = it }, onBrowseReceived = { tab = BadgeSheetTab.Received },
                    )
                    tab == BadgeSheetTab.Received -> ReceivedTab(
                        awards = management.awards, loading = management.busy, canLoadMore = management.canLoadMore, editable = management.ready,
                        shownState = ::shownState, onToggle = ::toggle, onOpen = { detail = it },
                        onLoadMore = { scope.launch { store.loadAwards(reset = false) } },
                    )
                    else -> {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = if (issuerScreen == IssuerScreen.List) 0.dp else 16.dp, vertical = 8.dp)) {
                            IssuedBadgesPanel(session, issuerScreen, onScreen = { issuerScreen = it }, onUserClick = { onDismiss(); onUserClick(it) })
                        }
                        if (issuerScreen == IssuerScreen.List) ExtendedFloatingActionButton(
                            onClick = { issuerScreen = IssuerScreen.Edit(null) },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            text = { Text("新しいバッジ") },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        )
                    }
                }
            }
            // Hidden on the issued tab, which has its own forms, unless closing is waiting on an answer.
            if (canSave && (tab != BadgeSheetTab.Issued || openDetail != null || confirmDiscard)) {
                BadgeSaveBar(
                    confirmDiscard = confirmDiscard && dirty, conflictOnly = conflictOnly,
                    saving = saving || management.busy, canSave = management.ready,
                    onDiscard = {
                        draft = null
                        if (confirmDiscard) onDismiss() else confirmDiscard = false
                    },
                    onKeepEditing = { confirmDiscard = false },
                    onSave = ::save,
                )
            }
        }
    }
}

@Composable
private fun DisplayedTab(
    draft: BadgeDraft,
    editable: Boolean,
    known: Map<String, BadgeDisplayItem>,
    expanded: List<BadgeSelectionItem>,
    repository: BadgeRepository?,
    pubkey: String,
    syncLabel: String?,
    onChange: (BadgeDraft) -> Unit,
    onOpen: (BadgeDisplayItem) -> Unit,
    onBrowseReceived: () -> Unit,
) {
    if (draft.items.isEmpty()) {
        BadgeEmptyState("プロフィールに表示しているバッジはありません") {
            OutlinedButton(onClick = onBrowseReceived) { Text("受け取ったバッジから選ぶ") }
        }
        return
    }
    fun resolve(item: BadgeSelectionItem): BadgeDisplayItem? =
        item.address?.value?.let(known::get) ?: if (item.address?.kind == 30009) repository?.display(item, pubkey) else null
    val rows = remember(draft.items, known) { draft.items.map(::resolve) }
    val preview = remember(expanded, known) { expanded.mapNotNull(::resolve) }
    val keys = remember(draft.items) { badgeRowKeys(draft.items) }
    val rowHeight = with(LocalDensity.current) { DisplayedRowHeight.toPx() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentDraft by rememberUpdatedState(draft)
    val currentOnChange by rememberUpdatedState(onChange)
    fun dragBy(key: String, delta: Float) {
        val items = currentDraft.items
        val index = badgeRowKeys(items).indexOf(key)
        if (index < 0) return
        val (target, rest) = badgeDragStep(index, items.lastIndex, dragOffset + delta, rowHeight)
        if (target != index) currentOnChange(currentDraft.moved(index, target))
        dragOffset = rest
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = dragKey == null).padding(vertical = 4.dp)) {
        BadgeSectionLabel("プロフィールでの見え方")
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                if (preview.isEmpty()) Text("表示できるバッジがまだ読み込まれていません", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                preview.forEach { badge -> Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { BadgeImage(badge) } }
            }
        }
        BadgeSectionLabel("表示順", trailing = {
            if (syncLabel != null) BadgeStatusChip(syncLabel)
            else if (draft.items.size > 1 && editable) Text("≡ をドラッグで並べ替え", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
        draft.items.forEachIndexed { index, item ->
            val key = keys[index]
            key(key) {
                val dragging = dragKey == key
                val badge = rows[index]
                val address = item.address
                val title = badge?.name ?: when {
                    address?.kind == 30008 -> "バッジセット「${address.identifier}」"
                    else -> "読み込み中のバッジ（${address?.identifier ?: "不明"}）"
                }
                val actions = buildList {
                    if (index > 0) add(CustomAccessibilityAction("先頭へ移動") { onChange(draft.moved(index, 0)); true })
                    if (index > 0) add(CustomAccessibilityAction("上へ移動") { onChange(draft.moved(index, index - 1)); true })
                    if (index < draft.items.lastIndex) add(CustomAccessibilityAction("下へ移動") { onChange(draft.moved(index, index + 1)); true })
                }
                Surface(
                    color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = if (dragging) 6.dp else 0.dp,
                    modifier = Modifier.zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .semantics { if (editable) customActions = actions },
                ) {
                    Row(Modifier.fillMaxWidth().height(DisplayedRowHeight).clickable(enabled = badge != null) { badge?.let(onOpen) }
                        .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (badge != null) BadgeTile(badge, 40) else PlaceholderTile(40)
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val supporting = when {
                                badge != null -> formatTimestamp(badge.awardedAt)
                                address?.kind == 30008 -> "まとめて表示するバッジセット"
                                else -> null
                            }
                            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        }
                        IconButton(enabled = editable, onClick = { onChange(draft.removedAt(index)) }) {
                            Icon(Icons.Default.Close, contentDescription = "「$title」を表示から外す")
                        }
                        if (draft.items.size > 1) Box(
                            Modifier.size(40.dp, 48.dp).then(if (editable) Modifier.pointerInput(key) {
                                detectDragGestures(
                                    onDragStart = { dragKey = key; dragOffset = 0f },
                                    onDragEnd = { dragKey = null; dragOffset = 0f },
                                    onDragCancel = { dragKey = null; dragOffset = 0f },
                                    onDrag = { change, amount -> change.consume(); dragBy(key, amount.y) },
                                )
                            } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.DragHandle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (editable) 1f else 0.38f))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReceivedTab(
    awards: List<BadgeDisplayItem>,
    loading: Boolean,
    canLoadMore: Boolean,
    editable: Boolean,
    shownState: (String) -> BadgeShownState,
    onToggle: (BadgeDisplayItem) -> Unit,
    onOpen: (BadgeDisplayItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    val issuers = rememberIssuerProfiles(awards)
    if (awards.isEmpty()) {
        if (loading) Column(Modifier.padding(vertical = 8.dp)) { repeat(3) { SkeletonRow() } }
        else BadgeEmptyState("受け取ったバッジはありません")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(awards, key = { it.address }) { badge ->
            val issuerName = issuers[badge.issuer]?.bestName ?: shortPubkey(badge.issuer)
            BadgeListRow(badge, onClick = { onOpen(badge) }, supporting = "$issuerName ・ ${formatTimestamp(badge.awardedAt)}") {
                when (shownState(badge.address)) {
                    BadgeShownState.Hidden -> FilledTonalButton(enabled = editable, onClick = { onToggle(badge) },
                        contentPadding = PaddingValues(horizontal = 14.dp)) { Text("表示する") }
                    BadgeShownState.Shown -> TextButton(enabled = editable, onClick = { onToggle(badge) }) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("表示中")
                    }
                    BadgeShownState.ShownViaSet -> Text("セットで表示中", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
        if (canLoadMore) item {
            OutlinedButton(enabled = !loading, onClick = onLoadMore, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(if (loading) "読み込んでいます…" else "さらに読み込む")
            }
        }
    }
}

@Composable
private fun BadgeDisplayToggle(state: BadgeShownState, enabled: Boolean, onToggle: () -> Unit) {
    when (state) {
        BadgeShownState.Hidden -> Button(enabled = enabled, onClick = onToggle, modifier = Modifier.fillMaxWidth()) { Text("プロフィールに表示する") }
        BadgeShownState.Shown -> OutlinedButton(enabled = enabled, onClick = onToggle, modifier = Modifier.fillMaxWidth()) { Text("表示から外す") }
        BadgeShownState.ShownViaSet -> Text("バッジセットとしてまとめて表示しています。このバッジだけを外すことはできません。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BadgeSaveBar(
    confirmDiscard: Boolean,
    conflictOnly: Boolean,
    saving: Boolean,
    canSave: Boolean,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when {
                    confirmDiscard -> "未保存の変更があります"
                    conflictOnly -> "いまの内容で保存し直してください"
                    else -> "表示バッジを変更しました"
                },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (confirmDiscard) {
                    TextButton(onClick = onDiscard) { Text("破棄して閉じる", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onKeepEditing) { Text("戻る") }
                } else if (!conflictOnly) TextButton(enabled = !saving, onClick = onDiscard) { Text("破棄") }
                Button(enabled = canSave && !saving, onClick = onSave) { Text(if (saving) "保存中…" else "保存") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared parts
// ---------------------------------------------------------------------------------------------

@Composable
private fun BadgeSheetHeader(title: String, count: Int? = null, onBack: (() -> Unit)? = null, onRefresh: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = if (onBack != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (count != null) Text("  $count", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "再読み込み") }
    }
}

@Composable
private fun BadgeSheetTabs(selected: BadgeSheetTab, onSelect: (BadgeSheetTab) -> Unit, counts: Map<BadgeSheetTab, Int>, marked: BadgeSheetTab?) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        BadgeSheetTab.entries.forEach { tab ->
            Tab(selected = tab == selected, onClick = { onSelect(tab) }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tab.label, maxLines = 1)
                    counts[tab]?.takeIf { it > 0 }?.let {
                        Text(" $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (tab == marked) Box(Modifier.padding(start = 4.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            })
        }
    }
}

/** Always takes its height so the content does not move when loading starts or ends. */
@Composable
private fun BadgeSheetProgress(active: Boolean) {
    Box(Modifier.fillMaxWidth().height(2.dp)) { if (active) LinearProgressIndicator(Modifier.fillMaxSize()) }
}

@Composable
private fun BadgeNoticeBanner(notice: BadgeNotice, onAction: (BadgeNoticeAction) -> Unit) {
    val (container, content) = when (notice.tone) {
        BadgeNoticeTone.Info -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        BadgeNoticeTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        BadgeNoticeTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(notice.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            notice.action?.let { action ->
                TextButton(onClick = { onAction(action) }, colors = ButtonDefaults.textButtonColors(contentColor = content)) { Text(action.label) }
            }
        }
    }
}

@Composable
private fun BadgeSectionLabel(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Small outlined label for a sync state such as 送信待ち. */
@Composable
internal fun BadgeStatusChip(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = Color.Transparent) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1)
    }
}

@Composable
internal fun BadgeEmptyState(text: String, supporting: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        action?.invoke()
    }
}

/** A badge row: tile, name, one supporting line, and an optional trailing control. */
@Composable
internal fun BadgeListRow(badge: BadgeDisplayItem, onClick: () -> Unit, supporting: String?, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BadgeTile(badge, 40)
        Column(Modifier.weight(1f)) {
            Text(badge.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing()
    }
}

@Composable
internal fun BadgeTile(badge: BadgeDisplayItem, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center) { BadgeImage(badge, size) }
}

@Composable
private fun PlaceholderTile(size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SkeletonRow() {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val color = MaterialTheme.colorScheme.surfaceContainerHighest
        Box(Modifier.size(40.dp).background(color, RoundedCornerShape(10.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(140.dp).height(12.dp).background(color, RoundedCornerShape(6.dp)))
            Box(Modifier.width(90.dp).height(10.dp).background(color, RoundedCornerShape(5.dp)))
        }
    }
}

/** Badge, description, issuer and award date; used by the sheet's detail page and by BadgeDetailDialog. */
@Composable
internal fun BadgeDetailContent(badge: BadgeDisplayItem, issuer: NostrProfile?, onIssuerClick: (String) -> Unit) {
    LaunchedEffect(badge.issuer) { ProfileRepository.ensureProfiles(setOf(badge.issuer), ProfileFetchPolicy.CacheFirst(300_000)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BadgeTile(badge, 112)
        Text(badge.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (badge.description.isNotBlank()) Text(badge.description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onIssuerClick(badge.issuer) }.padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("発行者", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    AvatarCircle(pubkey = badge.issuer, name = issuer?.bestName, pictureUrl = issuer?.picture, size = 22)
                    Text(issuer?.bestName ?: shortPubkey(badge.issuer), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f, fill = false))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("授与日時", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(formatTimestamp(badge.awardedAt), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Issuer profiles for a list of badges, fetched once per set of issuers. */
@Composable
private fun rememberIssuerProfiles(badges: List<BadgeDisplayItem>): Map<String, NostrProfile> {
    val issuers = remember(badges) { badges.mapTo(mutableSetOf()) { it.issuer } }
    val flow = remember(issuers) { ProfileRepository.observe(issuers) }
    LaunchedEffect(issuers) { if (issuers.isNotEmpty()) ProfileRepository.ensureProfiles(issuers, ProfileFetchPolicy.CacheFirst(300_000)) }
    return flow.collectAsState(initial = remember(issuers) { ProfileRepository.getCached(issuers) }).value
}

private fun shortPubkey(pubkey: String) = pubkey.take(8) + "…" + pubkey.takeLast(8)
