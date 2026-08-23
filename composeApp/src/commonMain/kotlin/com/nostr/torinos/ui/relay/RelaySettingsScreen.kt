package com.nostr.torinos.ui.relay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.nostr.torinos.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayInformation
import com.nostr.torinos.network.RelayLimitation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsScreen(
    onBack: () -> Unit,
    viewModel: RelaySettingsViewModel = accountSessionViewModel("relay-settings") { accountSession ->
        RelaySettingsViewModel(accountSession)
    },
) {
    val entries by viewModel.entries.collectAsState()
    val informationState by viewModel.informationState.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()
    val publishedRelayListState by viewModel.publishedRelayListState.collectAsState()
    var input by remember { mutableStateOf("") }
    var showDisabledRelays by remember { mutableStateOf(false) }
    val searchQuery = input.trim()
    val isSearching = searchQuery.isNotEmpty()
    val stagedRelayUrls = publishedRelayListState.pendingAdditions +
        publishedRelayListState.pendingRemovals
    val filteredEntries = visibleRelayEntries(
        entries = entries,
        stagedRelayUrls = stagedRelayUrls,
        searchQuery = searchQuery,
    )
    val enabledEntries = filteredEntries.filter { it.enabled }
    val disabledEntries = filteredEntries.filterNot { it.enabled }
    val areDisabledRelaysVisible = isSearching || showDisabledRelays

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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 追加フォーム
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("リレーを追加・検索") },
                    placeholder = { Text("wss://relay.example.com") },
                    singleLine = true,
                    enabled = !publishedRelayListState.isPublishing,
                )
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            viewModel.add(input)
                            input = ""
                        }
                    },
                    enabled = !publishedRelayListState.isPublishing,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "追加")
                }
            }

            HorizontalDivider()

            if (publishedRelayListState.hasChanges) {
                PublishedRelayDiff(
                    additions = publishedRelayListState.pendingAdditions,
                    removals = publishedRelayListState.pendingRemovals,
                    isPublishing = publishedRelayListState.isPublishing,
                    canUpdate = publishedRelayListState.canPublishChanges,
                    onCancel = viewModel::cancelRelayListChanges,
                    onUpdate = viewModel::publishRelayListChanges,
                )
                HorizontalDivider()
            }

            when {
                publishedRelayListState.isAwaitingFirstResponse -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "公開リレーを取得中",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                publishedRelayListState.errorMessage != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = publishedRelayListState.errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::refreshPublishedRelayList) {
                            Text("再試行")
                        }
                    }
                }
                publishedRelayListState.message != null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = publishedRelayListState.message.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (publishedRelayListState.failedRelayUrls.isNotEmpty()) {
                            Text(
                                text = publishedRelayListState.failedRelayUrls.joinToString(separator = "\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            when {
                discoveryState.isLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "フォロー先のリレーを取得中",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                discoveryState.errorMessage != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = discoveryState.errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::retryFollowedRelayDiscovery) {
                            Text("再試行")
                        }
                    }
                }
                discoveryState.message != null -> {
                    Text(
                        text = discoveryState.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(enabledEntries, key = { it.url }) { entry ->
                    RelayRow(
                        entry = entry,
                        onToggle = { viewModel.setEnabled(entry.url, it) },
                        enabled = !publishedRelayListState.isPublishing,
                        onShowInformation = { viewModel.showRelayInformation(entry.url) },
                    )
                    HorizontalDivider()
                }
                if (disabledEntries.isNotEmpty()) {
                    item(key = "disabled-relays-header") {
                        DisabledRelaysHeader(
                            count = disabledEntries.size,
                            expanded = areDisabledRelaysVisible,
                            canToggle = !isSearching,
                            onToggle = { showDisabledRelays = !showDisabledRelays },
                        )
                        HorizontalDivider()
                    }
                }
                if (areDisabledRelaysVisible) {
                    items(disabledEntries, key = { it.url }) { entry ->
                        RelayRow(
                            entry = entry,
                            onToggle = { viewModel.setEnabled(entry.url, it) },
                            enabled = !publishedRelayListState.isPublishing,
                            onShowInformation = { viewModel.showRelayInformation(entry.url) },
                        )
                        HorizontalDivider()
                    }
                }
                if (isSearching && filteredEntries.isEmpty()) {
                    item(key = "relay-search-empty") {
                        Text(
                            text = "一致するリレーはありません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }

        }
    }

    if (informationState.relayUrl != null) {
        RelayInformationDialog(
            state = informationState,
            onDismiss = viewModel::dismissRelayInformation,
            onRefresh = viewModel::refreshRelayInformation,
        )
    }
}

internal fun visibleRelayEntries(
    entries: List<RelayEntry>,
    stagedRelayUrls: Set<String>,
    searchQuery: String,
): List<RelayEntry> = entries.filter { entry ->
    entry.url !in stagedRelayUrls &&
        (searchQuery.isEmpty() || entry.url.contains(searchQuery, ignoreCase = true))
}

@Composable
private fun PublishedRelayDiff(
    additions: Set<String>,
    removals: Set<String>,
    isPublishing: Boolean,
    canUpdate: Boolean,
    onCancel: () -> Unit,
    onUpdate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        additions.forEach { url ->
            RelayDiffRow(
                icon = Icons.Default.Add,
                url = url,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        removals.forEach { url ->
            RelayDiffRow(
                icon = Icons.Default.Remove,
                url = url,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.align(Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !isPublishing,
            ) {
                Text("キャンセル")
            }
            Button(
                onClick = onUpdate,
                enabled = canUpdate,
            ) {
                if (isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("更新")
                }
            }
        }
    }
}

@Composable
private fun RelayDiffRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    url: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DisabledRelaysHeader(
    count: Int,
    expanded: Boolean,
    canToggle: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "無効なリレー $count 件",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (canToggle) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "無効なリレーを折りたたむ" else "無効なリレーを表示",
                )
            }
        }
    }
}

@Composable
private fun RelayRow(
    entry: RelayEntry,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean,
    onShowInformation: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onShowInformation)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Checkbox(
            checked = entry.enabled,
            onCheckedChange = onToggle,
            enabled = enabled,
        )
        Text(
            text = entry.url,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onShowInformation) {
            Icon(
                Icons.Default.Info,
                contentDescription = "リレー情報",
            )
        }
    }
}

@Composable
private fun RelayInformationDialog(
    state: RelayInformationUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("リレー情報") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = state.relayUrl.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    state.isLoading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("取得中")
                        }
                    }
                    state.errorMessage != null -> {
                        Text(
                            text = state.errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.information != null -> {
                        RelayInformationContent(state.information)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = !state.isLoading) {
                Text("再読み込み")
            }
        },
    )
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
