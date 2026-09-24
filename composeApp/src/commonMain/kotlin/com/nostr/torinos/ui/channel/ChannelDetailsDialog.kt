package com.nostr.torinos.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nostr.torinos.model.ChannelMetadataResolver
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.settings.setPlainText
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val prettyChannelEventJson = Json { prettyPrint = true }

@Composable
internal fun ChannelDetailsDialog(
    state: ChannelListViewModel.DetailDialogState,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val kind40Events = state.events.filter { it.kind == 40 }
    val kind41Events = state.events.filter { it.kind == 41 }
    val allJson = remember(state.events) {
        state.events.joinToString("\n\n") { event -> event.prettyJson() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppTopBar(
                    title = "チャンネル詳細",
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { coroutineScope.launch { clipboard.setPlainText(allJson) } },
                            enabled = state.events.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "JSONをすべてコピー")
                        }
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.isLoading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                            Text("KIND 40 / 41 を取得中…")
                        }
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    ChannelOverview(state = state)
                    EventKindSection(kind = 40, events = kind40Events)
                    EventKindSection(kind = 41, events = kind41Events)
                }
            }
        }
    }
}

@Composable
private fun ChannelOverview(state: ChannelListViewModel.DetailDialogState) {
    val resolution = remember(state.channelId, state.events) {
        ChannelMetadataResolver.resolve(
            channelId = state.channelId,
            createCandidates = state.events.filter { it.kind == 40 },
            updateCandidates = state.events.filter { it.kind == 41 },
        )
    }
    val info = resolution?.let(ChannelInfo::from) ?: ChannelInfo(
        channelId = state.channelId,
        ownerPubkey = null,
        meta = com.nostr.torinos.model.ChannelMeta(name = state.channelName),
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "現在のチャンネル情報",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            // チャンネル画面の情報ダイアログと同じ表示(FR-11)。購読先は持たず、作成者はキャッシュ済みプロフィールだけ使う。
            ChannelInfoContent(
                info = info,
                ownerProfile = info.ownerPubkey?.let { ProfileRepository.getCached(it) },
                onUserClick = null,
            )
        }
    }
}

@Composable
private fun EventKindSection(kind: Int, events: List<NostrEvent>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "KIND $kind (${events.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (events.isEmpty()) {
            Text(
                text = "イベントが見つかりません",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            events.forEach { event ->
                SelectionContainer {
                    Text(
                        text = event.prettyJson(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

private fun NostrEvent.prettyJson(): String =
    prettyChannelEventJson.encodeToString(NostrEvent.serializer(), this)
