package com.nostr.torinos.ui.notification

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.network.TargetLoadState
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.stripImageUrls
import com.nostr.torinos.ui.settings.setPlainText
import kotlinx.coroutines.launch

fun targetStatusText(reference: TargetReference, state: TargetLoadState?): String = when (reference) {
    TargetReference.AddressOnly -> "この参照形式は未対応"
    TargetReference.Invalid, TargetReference.None -> "対象への参照を確認できません"
    is TargetReference.EventId -> when (state) {
        null, TargetLoadState.Idle -> "対象を取得待ち"
        TargetLoadState.Loading -> "対象を読み込み中"
        TargetLoadState.NotFoundInQueriedRelays -> "接続先のリレーでは対象が見つかりませんでした"
        is TargetLoadState.Unavailable -> "対象を取得できませんでした"
        is TargetLoadState.Resolved -> ""
    }
}

@Composable
fun NotificationTargetPreview(
    reference: TargetReference,
    state: TargetLoadState?,
    profile: NostrProfile?,
    onRetry: (String) -> Unit,
    onOpenTarget: (NotificationTargetDestination) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column {
        val event = (state as? TargetLoadState.Resolved)?.event
        if (event == null) {
            Text(targetStatusText(reference, state), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reference is TargetReference.EventId &&
                (state is TargetLoadState.Unavailable || state == TargetLoadState.NotFoundInQueriedRelays)) {
                TextButton(onClick = { onRetry(reference.id) }) { Text("再試行") }
            }
        } else {
            val author = profile?.bestName ?: event.shortPubkey
            val body = stripImageUrls(stripNostrEventUris(notificationTargetBody(event)))
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(120)
            Text("$author: ${body.ifBlank { "ポスト" }}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val destination = notificationTargetDestination(event)
            when (destination) {
                is NotificationTargetDestination.Article -> TextButton(onClick = { onOpenTarget(destination) }) {
                    Text("記事の最新版を開く")
                }
                is NotificationTargetDestination.Live -> TextButton(onClick = { onOpenTarget(destination) }) {
                    Text("ライブを開く")
                }
                null -> {
                    Text(formatTimestamp(event.createdAt), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { scope.launch { clipboard.setPlainText(event.id) } }) {
                        Text("イベントIDをコピー")
                    }
                }
                else -> Unit
            }
        }
    }
}
