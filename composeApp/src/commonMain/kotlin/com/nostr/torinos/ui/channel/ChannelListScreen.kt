package com.nostr.torinos.ui.channel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    onChannelClick: (channelId: String) -> Unit = {},
) {
    val viewModel: ChannelListViewModel = viewModel(key = "channel-list") { ChannelListViewModel() }
    val state by viewModel.state.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("チャンネル") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        floatingActionButton = {
            if (state is ChannelListViewModel.UiState.Ready) {
                FloatingActionButton(onClick = viewModel::showCreateDialog) {
                    Icon(Icons.Default.Add, contentDescription = "新規チャンネル")
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is ChannelListViewModel.UiState.Loading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "チャンネルを読み込み中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is ChannelListViewModel.UiState.Ready -> {
                    if (s.channels.isEmpty()) {
                        Text(
                            text = "チャンネルがありません",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(s.channels, key = { it.event.id }) { item ->
                                ChannelRow(
                                    item = item,
                                    onClick = { onChannelClick(item.event.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    // 新規チャンネル作成ダイアログ
    val dialog = (state as? ChannelListViewModel.UiState.Ready)?.createDialog
    if (dialog != null) {
        AlertDialog(
            onDismissRequest = { if (!dialog.isCreating) viewModel.dismissCreateDialog() },
            title = { Text("新規チャンネル") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dialog.name,
                        onValueChange = viewModel::onCreateNameChange,
                        label = { Text("チャンネル名 *") },
                        singleLine = true,
                        enabled = !dialog.isCreating,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = dialog.about,
                        onValueChange = viewModel::onCreateAboutChange,
                        label = { Text("説明") },
                        maxLines = 3,
                        enabled = !dialog.isCreating,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (dialog.error != null) {
                        Text(
                            text = dialog.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::createChannel,
                    enabled = dialog.name.isNotBlank() && !dialog.isCreating,
                ) {
                    if (dialog.isCreating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("作成")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissCreateDialog,
                    enabled = !dialog.isCreating,
                ) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
private fun ChannelRow(item: ChannelItem, onClick: () -> Unit) {
    val authorName = item.authorProfile?.bestName
        ?: item.event.pubkey.take(8) + "…"
    val activityTime = item.lastActivityAt ?: item.event.createdAt
    val timeText = relativeTime(activityTime)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.meta.name.ifBlank { "（名前なし）" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.meta.about.isNotBlank()) {
                Text(
                    text = item.meta.about,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$authorName · $timeText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 12.dp),
        ) {
            if (item.messageCount > 0) {
                Text(
                    text = "${item.messageCount}件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun relativeTime(epochSeconds: Long): String {
    val now = Clock.System.now().epochSeconds
    val diff = now - epochSeconds
    return when {
        diff < 60L -> "たった今"
        diff < 3600L -> "${diff / 60L}分前"
        diff < 86400L -> "${diff / 3600L}時間前"
        diff < 86400L * 30L -> "${diff / 86400L}日前"
        else -> "${diff / (86400L * 30L)}ヶ月前"
    }
}
