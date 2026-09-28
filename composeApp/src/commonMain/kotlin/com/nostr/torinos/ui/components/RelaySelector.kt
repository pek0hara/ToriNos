package com.nostr.torinos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostr.torinos.network.NostrRepository
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
    selectedRelayNameFontSize: TextUnit = 16.sp,
    showSelectedRelayUrl: Boolean = true,
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

    Row(
        modifier = modifier,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSelectedRelayUrl && selectedRelayUrl != null) {
                Text(
                    text = selectedRelayUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "リレー切り替え",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("リレー設定") },
                onClick = {
                    expanded = false
                    onOpenRelaySettings()
                },
            )
            relays.forEach { url ->
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RelayConnectionDot(
                                state = relayConnectionStates[url] ?: RelayConnectionState.Disconnected,
                            )
                            Text(url.relayDisplayName())
                        }
                    },
                    onClick = {
                        onRelaySelected(url)
                        expanded = false
                    },
                    trailingIcon = if (url == selectedRelayUrl) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
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
            .clip(CircleShape)
            .background(color),
    )
}

private fun String.relayDisplayName(): String =
    removePrefix("wss://")
        .removePrefix("ws://")
        .trimEnd('/')
