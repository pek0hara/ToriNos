package com.nostr.torinos.ui.channel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.ChannelMetadataResolution
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ChannelLocalState
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.profile.AvatarCircle

/** チャンネル情報画面の表示内容(FR-11)。チャンネル画面と一覧の詳細で共通。 */
data class ChannelInfo(
    val channelId: String,
    val ownerPubkey: String?,
    val meta: ChannelMeta,
    val createdAt: Long? = null,
    /** 実効メタデータが kind 41 由来のときの更新日時。 */
    val updatedAt: Long? = null,
    /** 実効メタデータの取得元(40 / 41)。不明なら null。 */
    val sourceKind: Int? = null,
    val sourceEventId: String? = null,
    val createClient: String? = null,
    val updateClient: String? = null,
) {
    companion object {
        internal fun from(resolution: ChannelMetadataResolution): ChannelInfo {
            val effective = resolution.effectiveEvent
            val update = effective.takeIf { it.kind == 41 }
            return ChannelInfo(
                channelId = resolution.channelCreateEvent.id,
                ownerPubkey = resolution.channelCreateEvent.pubkey,
                meta = resolution.metadata,
                createdAt = resolution.channelCreateEvent.createdAt,
                updatedAt = update?.createdAt,
                sourceKind = effective.kind,
                sourceEventId = effective.id,
                createClient = resolution.channelCreateEvent.clientName,
                updateClient = update?.clientName,
            )
        }

        /** 端末に保存済みの状態から作る(kind 40 受信前の暫定表示)。 */
        internal fun from(state: ChannelLocalState): ChannelInfo = ChannelInfo(
            channelId = state.channelId,
            ownerPubkey = state.ownerPubkey.ifBlank { null },
            meta = state.meta,
            createdAt = state.channelCreatedAt.takeIf { it > 0 },
            updatedAt = state.metadataCreatedAt.takeIf { state.metadataKind == 41 },
            sourceKind = state.metadataKind.takeIf { state.hasMetadata },
            sourceEventId = state.metadataEventId.ifBlank { null },
        )
    }
}

/**
 * チャンネル情報の共通表示(FR-11、第16.13節)。[subscribedRelays] はチャンネル画面の実際の購読先で、
 * 一覧の詳細では渡さない。イベント JSON の診断表示は一覧側の [ChannelDetailsDialog] だけに置く。
 */
@Composable
internal fun ChannelInfoContent(
    info: ChannelInfo,
    ownerProfile: NostrProfile?,
    onUserClick: ((String) -> Unit)?,
    subscribedRelays: List<String>? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            info.meta.picture.takeIf { it.isNotBlank() }?.let { picture ->
                NetworkImage(
                    url = picture,
                    contentDescription = "チャンネル画像",
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                    maxDecodeSizePx = 256,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = info.meta.name.ifBlank { "（名前なし）" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                info.sourceKind?.let { kind ->
                    Text(
                        text = if (kind == 41) "最新の kind 41 を反映" else "kind 40 の作成情報",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (info.meta.about.isNotBlank()) {
            LinkedText(
                text = info.meta.about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onProfileClick = onUserClick,
            )
        }
        info.ownerPubkey?.let { owner ->
            Row(
                modifier = Modifier.clickable(enabled = onUserClick != null) { onUserClick?.invoke(owner) },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarCircle(pubkey = owner, name = ownerProfile?.bestName, pictureUrl = ownerProfile?.picture, size = 32)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("作成者", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProfileNameText(
                        profile = ownerProfile,
                        fallback = owner.take(8) + "…" + owner.takeLast(8),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        InfoValue("チャンネルID（kind 40）", info.channelId, monospace = true)
        info.createdAt?.let { InfoValue("作成日時", formatTimestamp(it)) }
        info.createClient?.let { InfoValue("作成クライアント", it) }
        info.updatedAt?.let { InfoValue("最終更新日時", formatTimestamp(it)) }
        info.updateClient?.let { InfoValue("更新クライアント", it) }
        info.sourceEventId?.takeIf { info.sourceKind == 41 }?.let { InfoValue("実効メタデータの取得元（kind 41）", it, monospace = true) }
        InfoValue(
            label = "推奨リレー (${info.meta.relays.size})",
            value = info.meta.relays.joinToString("\n").ifBlank { "なし（あなたのリレーを使用）" },
            monospace = info.meta.relays.isNotEmpty(),
        )
        subscribedRelays?.let { relays ->
            InfoValue("実際の購読先 (${relays.size})", relays.joinToString("\n").ifBlank { "なし" }, monospace = true)
        }
    }
}

@Composable
private fun InfoValue(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                text = value,
                style = if (monospace) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            )
        }
    }
}
