package com.nostr.torinos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.nostr.torinos.chat.GroupFlavor
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayInformation
import com.nostr.torinos.ui.chat.ChatRelayIcon
import com.nostr.torinos.ui.relay.relayIconUrl
import kotlinx.coroutines.launch
import com.nostr.torinos.network.RelayConnectionState
import com.nostr.torinos.network.RelayInformationRepository

@Composable
fun RelaySelector(
    relays: List<String>,
    selectedRelayUrl: String?,
    onRelaySelected: (String) -> Unit,
    onOpenRelaySettings: () -> Unit,
    modifier: Modifier = Modifier,
    selectedRelayName: String? = null,
    // 通常の投稿の本文と同じ大きさ。
    selectedRelayNameFontSize: TextUnit = MaterialTheme.typography.bodyMedium.fontSize,
    showSelectedRelayUrl: Boolean = true,
    menuDescription: String? = null,
) {
    val relayConnectionStates by NostrRepository.relayConnectionStates.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val providedRelayName = selectedRelayName?.trim()?.takeIf(String::isNotEmpty)
    var fetchedRelayName by remember(selectedRelayUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedRelayUrl, providedRelayName) {
        if (providedRelayName != null || selectedRelayUrl == null) return@LaunchedEffect
        fetchedRelayName = RelayInformationRepository.fetch(selectedRelayUrl).getOrNull()
            ?.name
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    // 開いたときに一覧のリレー名を引く(キャッシュがあればすぐ返る)。
    var menuRelayInfo by remember { mutableStateOf<Map<String, RelayInformation>>(emptyMap()) }
    LaunchedEffect(expanded, relays) {
        if (!expanded) return@LaunchedEffect
        relays.distinct().filter { it !in menuRelayInfo }.forEach { url ->
            launch {
                RelayInformationRepository.fetch(url).getOrNull()
                    ?.let { menuRelayInfo = menuRelayInfo + (url to it) }
            }
        }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "リレー切り替え") { expanded = true }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = providedRelayName
                        ?: fetchedRelayName
                        ?: selectedRelayUrl?.relayDisplayName()
                        ?: "—",
                    fontSize = selectedRelayNameFontSize,
                    // 下にURLを重ねても60dpのバーに収まるよう、タイトル用の高い行間を引き継がない。
                    lineHeight = selectedRelayNameFontSize * 1.25f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // チャットのヘッダーと同じく、補足はスキームを外したホスト。名前と同じなら出さない。
                val selectedHost = selectedRelayUrl?.relayDisplayName()
                val selectedName = providedRelayName ?: fetchedRelayName
                if (showSelectedRelayUrl && selectedHost != null && selectedName != null && selectedName != selectedHost) {
                    Text(
                        text = selectedHost,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }

        HeaderDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menuDescription?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (relays.isEmpty()) {
                Text(
                    "リレーがありません",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            relays.distinct().forEach { url ->
                val state = relayConnectionStates[url] ?: RelayConnectionState.Disconnected
                val host = url.relayDisplayName()
                // チャットの切り替えと同じ形: リレー名(NIP-11)と、違えばホストの2行。
                val information = menuRelayInfo[url]
                val name = information?.name?.trim()?.takeIf(String::isNotEmpty) ?: host
                HeaderDropdownMenuItem(
                    label = name,
                    supportingText = host.takeIf { it != name },
                    selected = url == selectedRelayUrl,
                    leadingIcon = { RelayMenuIcon(name, relayIconUrl(url, information?.icon), state) },
                    onClick = {
                        expanded = false
                        onRelaySelected(url)
                    },
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            HeaderDropdownMenuItem(
                label = "リレー設定",
                leadingIcon = {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                onClick = {
                    expanded = false
                    onOpenRelaySettings()
                },
            )
        }
    }
}

/**
 * リレーのアイコン(NIP-11 の icon、無ければ favicon)。読めるまでと読めないときは
 * チャットと同じ頭文字の丸。接続状態の点を右下に重ねる。
 */
@Composable
private fun RelayMenuIcon(name: String, iconUrl: String?, state: RelayConnectionState) {
    Box(modifier = Modifier.size(28.dp)) {
        ChatRelayIcon(name, GroupFlavor.Standard, size = 28.dp, iconUrl = iconUrl)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            RelayConnectionDot(state)
        }
    }
}

@Composable
private fun RelayConnectionDot(state: RelayConnectionState) {
    val color = when (state) {
        RelayConnectionState.Connected -> Color(0xFF0B8F55)
        RelayConnectionState.Connecting -> Color(0xFFC47700)
        RelayConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .semantics {
                contentDescription = when (state) {
                    RelayConnectionState.Connected -> "接続済み"
                    RelayConnectionState.Connecting -> "接続中"
                    RelayConnectionState.Disconnected -> "未接続"
                }
            }
            .clip(CircleShape)
            .background(color),
    )
}

private fun String.relayDisplayName(): String =
    removePrefix("wss://")
        .removePrefix("ws://")
        .trimEnd('/')
