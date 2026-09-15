package com.nostr.torinos.ui.post

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DraftListSheet(
    onDismiss: () -> Unit,
    onDraftClick: (PostMemoData) -> Unit,
    viewModel: JournalViewModel = accountSessionViewModel(
        key = "post-draft-list",
    ) { accountSession -> JournalViewModel(accountSession = accountSession) },
) {
    val state by viewModel.state.collectAsState()
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedMemoRelayUrl.collectAsState()
    val drafts = state.memos.sortedByDescending { it.displayTime }

    LaunchedEffect(relays, selectedRelayUrl) {
        if (relays.isNotEmpty()) {
            viewModel.loadAllMemos(selectedRelayUrl?.takeIf { it in relays } ?: relays.first())
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 640.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "下書き一覧",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            HorizontalDivider()
            when {
                state.isLoading && drafts.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                drafts.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "下書きはありません",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(drafts, key = { it.eventId }) { item ->
                        DraftRow(
                            item = item,
                            onClick = { onDraftClick(item.memo) },
                            onDelete = { viewModel.showDeleteDialog(item) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    state.deleteDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteDialog,
            title = { Text("下書きを削除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("この下書きの削除要求をリレーへ送信します。")
                    dialog.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
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
                ) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun DraftRow(
    item: JournalItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.memo.text.ifBlank {
                    if (item.memo.imageUrls.isNotEmpty()) "画像 ${item.memo.imageUrls.size} 件" else "（本文なし）"
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatTimestamp(item.displayTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "下書きを削除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
