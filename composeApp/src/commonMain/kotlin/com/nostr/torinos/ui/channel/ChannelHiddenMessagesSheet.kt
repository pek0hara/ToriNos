package com.nostr.torinos.ui.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.components.formatTimestamp

/**
 * 非表示にしたメッセージの管理(Loop 9)。読み込み済みのものは本文の先頭を出して個別に戻せる。
 * 読み込み範囲外の件数は「すべて表示に戻す」でまとめて戻す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelHiddenMessagesSheet(
    ready: ChannelViewModel.UiState.Ready,
    onUnhide: (String) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val notLoaded = (ready.hiddenCount - ready.hiddenMessages.size).coerceAtLeast(0)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "非表示にしたメッセージ (${ready.hiddenCount})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onUnhideAll, enabled = ready.hiddenCount > 0) { Text("すべて表示に戻す") }
            }
            Text(
                text = "非表示は自分の表示だけに効きます。表示に戻すと削除要求（kind 5）を送ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ready.hiddenCount == 0) {
                Text("非表示にしたメッセージはありません", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn {
                items(ready.hiddenMessages, key = { it.id }) { message ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = ready.profiles[message.pubkey]?.bestName ?: (message.pubkey.take(8) + "…"),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatTimestamp(message.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onUnhide(message.id) }) { Text("表示に戻す") }
                    }
                    HorizontalDivider()
                }
            }
            if (notLoaded > 0) {
                Text(
                    text = "ほかに読み込んでいないメッセージが ${notLoaded} 件あります",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
