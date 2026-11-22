package com.nostr.torinos.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nostr.torinos.chat.ChatHiddenRelaysStore
import com.nostr.torinos.chat.GroupFlavor
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.ui.chat.ChatRelayHeadline

/** チャットタブで非表示にしたリレー（この端末・このアカウントだけ）。ここから表示に戻す。 */
@Composable
internal fun ChatSettingsSection(ownPubkey: String) {
    val hidden by remember(ownPubkey) { ChatHiddenRelaysStore.hidden(ownPubkey) }.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp).animateContentSize()) {
        Text(
            text = "チャット",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        // チャットタブを隠していても、受け取った招待リンクから参加できるようにする。
        // 預けた招待はチャットタブの参加シートで確かめてから使う。
        com.nostr.torinos.ui.setup.KeySetupInviteSection(modifier = Modifier.padding(bottom = 4.dp))
        Text(
            text = "非表示のリレー",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        if (hidden.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "ありません。チャットの切り替えメニューでリレーを長押しすると、非表示にできます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    hidden.sorted().forEachIndexed { index, relayUrl ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        HiddenRelayRow(relayUrl = relayUrl, onShow = { ChatHiddenRelaysStore.show(ownPubkey, relayUrl) })
                    }
                }
            }
            Text(
                "参加はそのままです。表示に戻すと、チャットの一覧にまた出ます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun HiddenRelayRow(relayUrl: String, onShow: () -> Unit) {
    val host = relayUrl.substringAfter("://").trimEnd('/')
    val name by produceState<String?>(null, relayUrl) {
        value = RelayInformationRepository.fetch(relayUrl).getOrNull()?.name?.takeIf { it.isNotBlank() }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatRelayHeadline(
            label = name ?: host,
            host = host,
            flavor = GroupFlavor.Standard,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onShow, modifier = Modifier.padding(start = 8.dp)) {
            Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("表示", modifier = Modifier.padding(start = 6.dp))
        }
    }
}
