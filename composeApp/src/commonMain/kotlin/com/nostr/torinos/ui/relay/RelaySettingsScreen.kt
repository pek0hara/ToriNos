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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayInformation
import com.nostr.torinos.network.RelayLimitation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsScreen(
    onBack: () -> Unit,
    viewModel: RelaySettingsViewModel = viewModel { RelaySettingsViewModel() },
) {
    val entries by viewModel.entries.collectAsState()
    val informationState by viewModel.informationState.collectAsState()
    var input by remember { mutableStateOf("") }
    var showDisabledRelays by remember { mutableStateOf(false) }
    val enabledEntries = entries.filter { it.enabled }
    val disabledEntries = entries.filterNot { it.enabled }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("リレー設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.resetToDefaults() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "リレー設定をリセット",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
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
                    label = { Text("wss://relay.example.com") },
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            viewModel.add(input)
                            input = ""
                        }
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = "追加")
                }
            }

            HorizontalDivider()

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(enabledEntries, key = { it.url }) { entry ->
                    RelayRow(
                        entry = entry,
                        onToggle = { viewModel.setEnabled(entry.url, it) },
                        onDelete = { viewModel.remove(entry.url) },
                        onShowInformation = { viewModel.showRelayInformation(entry.url) },
                    )
                    HorizontalDivider()
                }
                if (disabledEntries.isNotEmpty()) {
                    item(key = "disabled-relays-header") {
                        DisabledRelaysHeader(
                            count = disabledEntries.size,
                            expanded = showDisabledRelays,
                            onToggle = { showDisabledRelays = !showDisabledRelays },
                        )
                        HorizontalDivider()
                    }
                }
                if (showDisabledRelays) {
                    items(disabledEntries, key = { it.url }) { entry ->
                        RelayRow(
                            entry = entry,
                            onToggle = { viewModel.setEnabled(entry.url, it) },
                            onDelete = { viewModel.remove(entry.url) },
                            onShowInformation = { viewModel.showRelayInformation(entry.url) },
                        )
                        HorizontalDivider()
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

@Composable
private fun DisabledRelaysHeader(
    count: Int,
    expanded: Boolean,
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
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "無効なリレーを折りたたむ" else "無効なリレーを表示",
            )
        }
    }
}

@Composable
private fun RelayRow(
    entry: RelayEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
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
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "削除",
                tint = MaterialTheme.colorScheme.error,
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
