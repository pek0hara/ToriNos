package com.nostr.torinos.ui.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsScreen(
    onBack: () -> Unit,
    viewModel: RelaySettingsViewModel = viewModel { RelaySettingsViewModel() },
) {
    val entries by viewModel.entries.collectAsState()
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
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "削除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
