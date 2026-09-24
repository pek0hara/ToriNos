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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.network.RelayConnectionState

/**
 * チャンネルの閲覧先・投稿先リレーの詳細(FR-09、FR-10、第16.13節)。
 * 推奨/fallback の区分、閲覧・投稿の使用有無、接続状態、直近投稿のリレー別結果を並べる。
 * 診断表示であり、ユーザーのリレー設定は変更しない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelRelayDetailsSheet(
    ready: ChannelViewModel.UiState.Ready,
    onDismiss: () -> Unit,
) {
    val rows = ChannelRelayPresentation.rows(ready.relayContext, ready.relayStates, ready.publishState, ready.relayRefusals)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("リレー", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (ready.relayContext.recommendedRelays.isEmpty()) {
                    "このチャンネルには推奨リレーがありません。あなたのリレーで閲覧・投稿します。"
                } else {
                    "チャンネル情報の推奨リレーと、あなたのリレーを合わせて使います。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ready.isRelayTransitioning) {
                Text(
                    text = "推奨リレーの変更を反映中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LazyColumn {
                items(rows, key = { it.url }) { row ->
                    ChannelRelayRowItem(row)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ChannelRelayRowItem(row: ChannelRelayRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.url.relayHost(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val (label, color) = connectionLabel(row.connection)
            Text(
                text = if (row.refusal != null) "購読拒否" else label,
                style = MaterialTheme.typography.labelSmall,
                color = if (row.refusal != null) MaterialTheme.colorScheme.error else color,
            )
        }
        row.refusal?.let { reason ->
            Text(
                text = "リレーの応答: $reason",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = buildList {
                add(if (row.isRecommended) "推奨" else "あなたのリレー")
                if (row.usedForRead) add("閲覧")
                if (row.usedForWrite) add("投稿")
            }.joinToString("・"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val publish = row.lastPublish) {
            null -> Unit
            ChannelRelayRow.LastPublish.Pending -> Text(
                "直近の投稿: 応答待ち",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChannelRelayRow.LastPublish.Succeeded -> Text(
                "直近の投稿: 成功",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is ChannelRelayRow.LastPublish.Failed -> Text(
                "直近の投稿: 失敗（${publish.reason}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun connectionLabel(state: RelayConnectionState?): Pair<String, Color> = when (state) {
    RelayConnectionState.Connected -> "接続中" to MaterialTheme.colorScheme.primary
    RelayConnectionState.Connecting -> "接続待ち" to MaterialTheme.colorScheme.onSurfaceVariant
    RelayConnectionState.Disconnected -> "切断" to MaterialTheme.colorScheme.error
    null -> "送信時に接続" to MaterialTheme.colorScheme.onSurfaceVariant
}
