package com.nostr.torinos.ui.relay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.network.RelayConnectionState
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayInformation
import com.nostr.torinos.network.RelayLimitation
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus
import com.nostr.torinos.ui.profile.FollowedUsersPage
import com.nostr.torinos.ui.profile.FollowedUsersSummary
import io.ktor.http.Url

/** 候補セクションを折りたたんでいるときに表示する件数。 */
private const val COLLAPSED_CANDIDATE_COUNT = 10

private val ConnectedColor = Color(0xFF0B8F55)
private val ConnectingColor = Color(0xFFC47700)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit = {},
    viewModel: RelaySettingsViewModel = accountSessionViewModel("relay-settings") { accountSession ->
        RelaySettingsViewModel(accountSession)
    },
) {
    DismissKeyboardOnLeave()
    val entries by viewModel.entries.collectAsState()
    val informationState by viewModel.informationState.collectAsState()
    val relayInformation by viewModel.relayInformation.collectAsState()
    val connectionStates by viewModel.connectionStates.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()
    val publishedRelayListState by viewModel.publishedRelayListState.collectAsState()
    val relayUsers by viewModel.relayUsers.collectAsState()
    val usageCounts = remember(relayUsers) { relayUsers.mapValues { it.value.size } }
    // 利用者一覧を表示しているシートのリレー。null ならシートは先頭の詳細を表示する。
    var usersPageUrl by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var showAllCandidates by remember { mutableStateOf(false) }
    val searchQuery = input.trim()
    val isSearching = searchQuery.isNotEmpty()
    val isEditable = !publishedRelayListState.isPublishing
    val sections = relaySections(
        entries = entries,
        pendingRemovals = publishedRelayListState.pendingRemovals,
        usageCounts = usageCounts,
        searchQuery = searchQuery,
        relayNames = relayInformation.mapValues { it.value.name },
    )
    val inputRelayUrl = relayUrlFromInput(input)
        ?.takeIf { url -> entries.none { it.url == url && it.enabled } }
    // 未保存の追加はまだ接続対象ではないため、接続状況の集計から外す。
    val connectionTargets = entries.filter { entry ->
        entry.enabled && entry.url !in publishedRelayListState.pendingAdditions
    }
    val connectedCount = connectionTargets.count { entry ->
        connectionStates[entry.url] == RelayConnectionState.Connected
    }
    val enabledCount = entries.count { it.enabled }
    val visibleCandidates = if (isSearching || showAllCandidates) {
        sections.candidates
    } else {
        sections.candidates.take(COLLAPSED_CANDIDATE_COUNT)
    }
    val showsBottomBar = publishedRelayListState.hasChanges || publishedRelayListState.isPublishing

    fun submitInput() {
        val url = inputRelayUrl ?: return
        viewModel.add(url)
        input = ""
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = { Text("リレー設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (showsBottomBar) {
                PendingChangesBar(
                    state = publishedRelayListState,
                    isAnonymous = viewModel.isAnonymous,
                    onCancel = viewModel::cancelRelayListChanges,
                    onUpdate = viewModel::publishRelayListChanges,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .avoidStaleAccessibilityFocus(),
                // 文字サイズを大きくしても欄の高さが変わらないよう、1行に収めて超えた分は省略する。
                placeholder = { Text("検索または wss:// で追加", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "入力を消去")
                        }
                    }
                },
                singleLine = true,
                enabled = isEditable,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submitInput() }),
            )

            // 一覧の先頭へ差し込むと、スクロール位置の維持で画面外に押し出されるため一覧の外に置く。
            if (inputRelayUrl != null) {
                AddRelayRow(
                    url = inputRelayUrl,
                    isKnown = entries.any { it.url == inputRelayUrl },
                    enabled = isEditable,
                    onAdd = ::submitInput,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val bannerError = publishedRelayListState.errorMessage
                ?.takeUnless { publishedRelayListState.hasChanges }
            val bannerModifier = Modifier.padding(horizontal = 16.dp)
            if (bannerError != null) {
                NoticeBanner(
                    text = bannerError,
                    isError = true,
                    actionLabel = "再試行",
                    onAction = viewModel::refreshPublishedRelayList,
                    modifier = bannerModifier,
                )
            } else if (publishedRelayListState.message != null) {
                NoticeBanner(
                    text = publishedRelayListState.message.orEmpty(),
                    detail = publishedRelayListState.failedRelayUrls
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(separator = "\n"),
                    modifier = bannerModifier,
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                if (viewModel.isAnonymous) {
                    item(key = "anonymous-note") {
                        NoticeText(
                            text = "未ログイン中のリレー設定です。変更はこの端末内に保存されます。",
                        )
                    }
                }

                if (!isSearching || sections.active.isNotEmpty()) {
                    item(key = "active-header") {
                        SectionHeader(
                            title = "使用中 $enabledCount",
                        ) {
                            if (publishedRelayListState.isAwaitingFirstResponse) {
                                ProgressLabel("公開リストを取得中")
                            } else if (connectionTargets.isNotEmpty()) {
                                ConnectionSummary(connected = connectedCount, total = connectionTargets.size)
                            }
                        }
                    }
                }
                items(sections.active, key = { "active-${it.url}" }) { entry ->
                    ActiveRelayRow(
                        entry = entry,
                        information = relayInformation[entry.url],
                        connectionState = connectionStates[entry.url],
                        usageCount = usageCounts[entry.url],
                        change = publishedRelayListState.changeOf(entry.url),
                        enabled = isEditable,
                        onClick = { viewModel.showRelayInformation(entry.url) },
                        onShowUsers = {
                            usersPageUrl = entry.url
                            viewModel.showRelayInformation(entry.url)
                        },
                        onSetReadWrite = { read, write -> viewModel.setReadWrite(entry.url, read, write) },
                        onDisable = { viewModel.setEnabled(entry.url, false) },
                        onRevert = { viewModel.revertChange(entry.url) },
                    )
                }
                if (!isSearching && sections.active.isEmpty()) {
                    item(key = "active-empty") {
                        NoticeText(text = "使用中のリレーはありません")
                    }
                }
                if (isSearching && inputRelayUrl == null && sections.active.isEmpty() && sections.candidates.isEmpty()) {
                    item(key = "search-empty") {
                        NoticeText(text = "一致するリレーはありません")
                    }
                }

                if (sections.candidates.isNotEmpty() || discoveryState.isLoading || discoveryState.errorMessage != null) {
                    item(key = "candidate-header") {
                        val hasUsage = usageCounts.isNotEmpty()
                        SectionHeader(
                            title = if (hasUsage) "フォロー先のリレー" else "その他のリレー",
                            subtitle = buildString {
                                append("${sections.candidates.size}件")
                                if (hasUsage) append(" · 利用人数の多い順")
                            },
                        ) {
                            when {
                                discoveryState.isLoading -> ProgressLabel("フォロー先を取得中")
                                discoveryState.errorMessage != null -> TextButton(
                                    onClick = viewModel::retryFollowedRelayDiscovery,
                                ) {
                                    Text("再取得")
                                }
                            }
                        }
                    }
                    discoveryState.errorMessage?.let { message ->
                        item(key = "discovery-error") {
                            NoticeText(text = message, isError = true)
                        }
                    }
                    discoveryState.message?.let { message ->
                        item(key = "discovery-message") {
                            NoticeText(text = message)
                        }
                    }
                }
                items(visibleCandidates, key = { "candidate-${it.url}" }) { entry ->
                    CandidateRelayRow(
                        entry = entry,
                        information = relayInformation[entry.url],
                        usageCount = usageCounts[entry.url],
                        enabled = isEditable,
                        onClick = { viewModel.showRelayInformation(entry.url) },
                        onShowUsers = {
                            usersPageUrl = entry.url
                            viewModel.showRelayInformation(entry.url)
                        },
                        onAdd = { viewModel.setEnabled(entry.url, true) },
                    )
                }
                if (!isSearching && sections.candidates.size > COLLAPSED_CANDIDATE_COUNT) {
                    item(key = "candidate-toggle") {
                        TextButton(
                            onClick = { showAllCandidates = !showAllCandidates },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (showAllCandidates) {
                                    "上位 $COLLAPSED_CANDIDATE_COUNT 件だけ表示"
                                } else {
                                    "すべて表示（${sections.candidates.size}件）"
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val sheetUrl = informationState.relayUrl
    if (sheetUrl != null) {
        RelayDetailSheet(
            url = sheetUrl,
            entry = entries.firstOrNull { it.url == sheetUrl },
            state = informationState,
            connectionState = connectionStates[sheetUrl],
            users = relayUsers[sheetUrl].orEmpty(),
            showsUsers = usersPageUrl == sheetUrl,
            onShowUsers = { usersPageUrl = sheetUrl },
            onHideUsers = { usersPageUrl = null },
            onOpenProfile = { pubkey ->
                // シートはプロフィールより手前に出るため、閉じてから開く。
                usersPageUrl = null
                viewModel.dismissRelayInformation()
                onOpenProfile(pubkey)
            },
            enabled = isEditable,
            onDismiss = {
                usersPageUrl = null
                viewModel.dismissRelayInformation()
            },
            onRefresh = viewModel::refreshRelayInformation,
            onSetEnabled = { viewModel.setEnabled(sheetUrl, it) },
            onSetReadWrite = { read, write -> viewModel.setReadWrite(sheetUrl, read, write) },
        )
    }
}

internal enum class RelayChange { None, Added, Removed, Updated }

internal fun PublishedRelayListUiState.changeOf(url: String): RelayChange = when (url) {
    in pendingAdditions -> RelayChange.Added
    in pendingRemovals -> RelayChange.Removed
    in pendingUpdates -> RelayChange.Updated
    else -> RelayChange.None
}

internal data class RelaySections(
    val active: List<RelayEntry>,
    val candidates: List<RelayEntry>,
)

/**
 * 使用中（外す予定を含む）と候補に分ける。
 * 未確定の変更も一覧に残し、候補はフォロー先の利用人数が多い順に並べる。
 */
internal fun relaySections(
    entries: List<RelayEntry>,
    pendingRemovals: Set<String>,
    usageCounts: Map<String, Int>,
    searchQuery: String,
    relayNames: Map<String, String?> = emptyMap(),
): RelaySections {
    val matched = entries.filter { entry ->
        searchQuery.isEmpty() ||
            entry.url.contains(searchQuery, ignoreCase = true) ||
            relayNames[entry.url]?.contains(searchQuery, ignoreCase = true) == true
    }
    val (active, candidates) = matched.partition { it.enabled || it.url in pendingRemovals }
    return RelaySections(
        active = active,
        candidates = candidates.sortedByDescending { usageCounts[it.url] ?: 0 },
    )
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun ProgressLabel(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionSummary(connected: Int, total: Int) {
    val color = when {
        connected == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        connected < total -> ConnectingColor
        else -> ConnectedColor
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = "$connected/$total 接続中",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoticeText(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun NoticeBanner(
    text: String,
    detail: String? = null,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun AddRelayRow(
    url: String,
    isKnown: Boolean,
    enabled: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onAdd)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (isKnown) "$url を使用する" else "$url を追加",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActiveRelayRow(
    entry: RelayEntry,
    information: RelayInformation?,
    connectionState: RelayConnectionState?,
    usageCount: Int?,
    change: RelayChange,
    enabled: Boolean,
    onClick: () -> Unit,
    onShowUsers: () -> Unit,
    onSetReadWrite: (read: Boolean, write: Boolean) -> Unit,
    onDisable: () -> Unit,
    onRevert: () -> Unit,
) {
    val isRemoved = change == RelayChange.Removed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RelayIcon(
            relayUrl = entry.url,
            iconUrl = relayIconUrl(entry.url, information?.icon),
            connectionState = if (isRemoved) null else connectionState ?: RelayConnectionState.Disconnected,
            dimmed = isRemoved,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = relayDisplayName(entry.url, information?.name),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (isRemoved) TextDecoration.LineThrough else null,
                    color = if (isRemoved) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ChangeLabel(change)
            }
            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!isRemoved) {
                ReadWriteChips(
                    read = entry.read,
                    write = entry.write,
                    enabled = enabled,
                    onChange = onSetReadWrite,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (usageCount != null && usageCount > 0) {
            UsageCount(usageCount, onClick = onShowUsers)
        }
        if (change != RelayChange.None) {
            IconButton(onClick = onRevert, enabled = enabled) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "${relayDisplayName(entry.url, information?.name)} の変更を取り消す",
                )
            }
        } else {
            RelayRowMenu(
                enabled = enabled,
                onShowInformation = onClick,
                onDisable = onDisable,
            )
        }
    }
}

@Composable
private fun RelayRowMenu(
    enabled: Boolean,
    onShowInformation: () -> Unit,
    onDisable: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "その他の操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("リレー情報") },
                onClick = {
                    expanded = false
                    onShowInformation()
                },
            )
            DropdownMenuItem(
                text = { Text("使用をやめる", color = MaterialTheme.colorScheme.error) },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onDisable()
                },
            )
        }
    }
}

@Composable
private fun ChangeLabel(change: RelayChange) {
    val (label, color) = when (change) {
        RelayChange.None -> return
        RelayChange.Added -> "追加" to MaterialTheme.colorScheme.primary
        RelayChange.Removed -> "外す" to MaterialTheme.colorScheme.error
        RelayChange.Updated -> "変更" to MaterialTheme.colorScheme.tertiary
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            maxLines = 1,
        )
    }
}

/** NIP-65 の受信・送信。最後に残った一方は外せない。 */
@Composable
private fun ReadWriteChips(
    read: Boolean,
    write: Boolean,
    enabled: Boolean,
    onChange: (read: Boolean, write: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModeChip(
            label = "受信",
            selected = read,
            enabled = enabled && !(read && !write),
            onClick = { onChange(!read, write) },
        )
        ModeChip(
            label = "送信",
            selected = write,
            enabled = enabled && !(write && !read),
            onClick = { onChange(read, !write) },
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val baseColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // 最後に残った一方は外せないため、押せないことが見て分かるよう薄くする。
    val color = if (enabled) baseColor else baseColor.copy(alpha = 0.55f)
    Surface(
        color = if (selected) color.copy(alpha = 0.14f) else Color.Transparent,
        contentColor = color,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = if (selected) 0.5f else 0.35f)),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textDecoration = if (selected) null else TextDecoration.LineThrough,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun CandidateRelayRow(
    entry: RelayEntry,
    information: RelayInformation?,
    usageCount: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
    onShowUsers: () -> Unit,
    onAdd: () -> Unit,
) {
    val name = relayDisplayName(entry.url, information?.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RelayIcon(
            relayUrl = entry.url,
            iconUrl = relayIconUrl(entry.url, information?.icon),
            connectionState = null,
            size = 40,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (usageCount != null && usageCount > 0) {
            UsageCount(usageCount, onClick = onShowUsers)
        }
        IconButton(onClick = onAdd, enabled = enabled) {
            Icon(
                Icons.Default.Add,
                contentDescription = "$name を使用する",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun UsageCount(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                onClickLabel = "利用しているフォロー先を表示",
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "フォロー先 $count 人が利用"
            }
            .minimumInteractiveComponentSize()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.People,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RelayIcon(
    relayUrl: String,
    iconUrl: String?,
    connectionState: RelayConnectionState?,
    size: Int = 44,
    dimmed: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier = Modifier.size(size.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                text = relayDisplayName(relayUrl, null).firstOrNull()?.uppercase() ?: "R",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            if (iconUrl != null) {
                NetworkImage(
                    url = iconUrl,
                    contentDescription = "${relayDisplayName(relayUrl, null)} のアイコン",
                    contentScale = ContentScale.Crop,
                    maxDecodeSizePx = 144,
                    animate = false,
                    modifier = Modifier
                        .size(size.dp)
                        .clip(shape),
                )
            }
            if (dimmed) {
                Box(
                    modifier = Modifier
                        .size(size.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                )
            }
        }
        if (connectionState != null) {
            ConnectionDot(
                state = connectionState,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun ConnectionDot(state: RelayConnectionState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(connectionColor(state))
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
    )
}

@Composable
private fun connectionColor(state: RelayConnectionState): Color = when (state) {
    RelayConnectionState.Connected -> ConnectedColor
    RelayConnectionState.Connecting -> ConnectingColor
    RelayConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
}

private fun connectionLabel(state: RelayConnectionState): String = when (state) {
    RelayConnectionState.Connected -> "オンライン"
    RelayConnectionState.Connecting -> "接続中"
    RelayConnectionState.Disconnected -> "未接続"
}

@Composable
private fun PendingChangesBar(
    state: PublishedRelayListUiState,
    isAnonymous: Boolean,
    onCancel: () -> Unit,
    onUpdate: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val warning = state.errorMessage
                ?: "送信できるリレーが残りません。いずれかのリレーで送信をオンにしてください。"
                    .takeUnless { state.hasWritableRelay }
            warning?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "変更 ${state.changeCount}件",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = listOfNotNull(
                            state.pendingAdditions.size.takeIf { it > 0 }?.let { "追加 $it" },
                            state.pendingRemovals.size.takeIf { it > 0 }?.let { "外す $it" },
                            state.pendingUpdates.size.takeIf { it > 0 }?.let { "変更 $it" },
                        ).joinToString(" · ").ifEmpty { if (state.isPublishing) "送信中" else "" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onCancel,
                    enabled = !state.isPublishing,
                ) {
                    Text("取り消す")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = onUpdate,
                    enabled = state.canPublishChanges,
                ) {
                    if (state.isPublishing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(if (isAnonymous) "保存する" else "公開する")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayDetailSheet(
    url: String,
    entry: RelayEntry?,
    state: RelayInformationUiState,
    connectionState: RelayConnectionState?,
    users: List<String>,
    showsUsers: Boolean,
    onShowUsers: () -> Unit,
    onHideUsers: () -> Unit,
    onOpenProfile: (String) -> Unit,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetReadWrite: (read: Boolean, write: Boolean) -> Unit,
) {
    val information = state.information
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (showsUsers) {
            FollowedUsersPage(
                title = "${relayDisplayName(url, information?.name)} を利用している人 ${users.size}人",
                users = users,
                onBack = onHideUsers,
                onOpenProfile = onOpenProfile,
            )
            return@ModalBottomSheet
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RelayIcon(
                    relayUrl = url,
                    iconUrl = relayIconUrl(url, information?.icon),
                    connectionState = if (entry?.enabled == true) {
                        connectionState ?: RelayConnectionState.Disconnected
                    } else {
                        null
                    },
                    size = 56,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = relayDisplayName(url, information?.name),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SelectionContainer {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = listOfNotNull(
                            if (entry?.enabled == true) {
                                connectionLabel(connectionState ?: RelayConnectionState.Disconnected)
                            } else {
                                "未使用"
                            },
                            users.size.takeIf { it > 0 }?.let { "フォロー先 $it 人が利用" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (entry != null) {
                if (entry.enabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReadWriteChips(
                            read = entry.read,
                            write = entry.write,
                            enabled = enabled,
                            onChange = onSetReadWrite,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                onSetEnabled(false)
                                onDismiss()
                            },
                            enabled = enabled,
                        ) {
                            Text("使用をやめる", color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            onSetEnabled(true)
                            onDismiss()
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("このリレーを使用する")
                    }
                }
            }

            if (users.isNotEmpty()) {
                HorizontalDivider()
                FollowedUsersSummary(
                    title = "利用しているフォロー先 ${users.size}人",
                    users = users,
                    onClick = onShowUsers,
                    avatarOutline = BottomSheetDefaults.ContainerColor,
                )
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "リレー情報",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Text("再読み込み")
                }
            }
            when {
                state.isLoading -> ProgressLabel("取得中")
                state.errorMessage != null -> Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                information != null -> RelayInformationContent(information)
            }
        }
    }
}

internal fun relayDisplayName(relayUrl: String, metadataName: String?): String =
    metadataName?.trim()?.takeIf { it.isNotEmpty() }
        ?: runCatching { Url(relayUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: relayUrl

internal fun relayIconUrl(relayUrl: String, metadataIcon: String?): String? {
    metadataIcon?.trim()?.takeIf { icon ->
        icon.startsWith("https://", ignoreCase = true) ||
            icon.startsWith("http://", ignoreCase = true)
    }?.let { return it }
    val host = runCatching { Url(relayUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return "https://$host/favicon.ico"
}


@Composable
private fun RelayInformationContent(information: RelayInformation) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoLine("名前", information.name)
        InfoLine("説明", information.description)
        InfoLine("連絡先", information.contact)
        InfoLine("公開鍵", information.pubkey)
        InfoLine("ソフトウェア", listOfNotNull(information.software, information.version).joinToString(" ").ifBlank { null })
        InfoLine("対応 NIP", information.supportedNips.sorted().joinToString(", ").ifBlank { null })
        InfoLine("国・地域", information.relayCountries.joinToString(", ").ifBlank { null })
        InfoLine("言語", information.languageTags.joinToString(", ").ifBlank { null })
        InfoLine("タグ", information.tags.joinToString(", ").ifBlank { null })
        InfoLine("投稿ポリシー", information.postingPolicy)
        InfoLine("支払い URL", information.paymentsUrl)
        information.limitation?.let { RelayLimitationContent(it) }
    }
}

@Composable
private fun RelayLimitationContent(limitation: RelayLimitation) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "制限",
            style = MaterialTheme.typography.titleSmall,
        )
        InfoLine("認証必須", limitation.authRequired?.toJapanese())
        InfoLine("支払い必須", limitation.paymentRequired?.toJapanese())
        InfoLine("書き込み制限", limitation.restrictedWrites?.toJapanese())
        InfoLine("最大メッセージ長", limitation.maxMessageLength?.toString())
        InfoLine("最大購読数", limitation.maxSubscriptions?.toString())
        InfoLine("最大フィルター数", limitation.maxFilters?.toString())
        InfoLine("最大 limit", limitation.maxLimit?.toString())
        InfoLine("最大 sub id 長", limitation.maxSubIdLength?.toString())
        InfoLine("最大イベントタグ数", limitation.maxEventTags?.toString())
        InfoLine("最大本文長", limitation.maxContentLength?.toString())
        InfoLine("最小 PoW 難易度", limitation.minPowDifficulty?.toString())
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun Boolean.toJapanese(): String = if (this) "はい" else "いいえ"
