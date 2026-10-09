package com.nostr.torinos.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.CancellationException

@Composable
internal fun PostRelayInfo(event: NostrEvent) {
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    var attempt by remember(event.id) { mutableStateOf(0) }
    var relays by remember(event.id) { mutableStateOf(NostrRepository.observedEventRelays(event.id)) }
    var loading by remember(event.id) { mutableStateOf(false) }
    var incomplete by remember(event.id) { mutableStateOf(false) }
    LaunchedEffect(event.id, expanded, attempt) {
        if (!expanded) return@LaunchedEffect
        loading = true
        incomplete = false
        try {
            incomplete = !PostRelayResolver().resolve(event) { relays = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            incomplete = true
        } finally {
            loading = false
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = if (expanded) "展開中" else "折りたたみ中" }
                .clickable(role = Role.Button, onClickLabel = if (expanded) "閉じる" else "リレーを確認") {
                    expanded = !expanded
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Dns,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "取得元リレー",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 40.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "閲覧リレーと投稿者の書き込み用リレーを確認します。元の送信先すべてを示すものではありません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                relays.sorted().forEach { relay ->
                    Text(relay, style = MaterialTheme.typography.bodySmall)
                }
                if (loading) {
                    Text("リレーを確認中…", style = MaterialTheme.typography.bodySmall)
                } else {
                    if (relays.isEmpty()) Text("取得元を確認できませんでした", style = MaterialTheme.typography.bodySmall)
                    if (incomplete) Text("一部の確認を完了できませんでした", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { attempt++ }) { Text("再確認") }
                }
            }
        }
    }
}
