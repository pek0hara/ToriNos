package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.ChannelRelayContext
import com.nostr.torinos.network.RelayConnectionState

/** リレー詳細シートの1行(第16.13節)。 */
internal data class ChannelRelayRow(
    val url: String,
    val isRecommended: Boolean,
    val usedForRead: Boolean,
    val usedForWrite: Boolean,
    /** null は購読していない(投稿時だけ一時接続する)リレー。 */
    val connection: RelayConnectionState?,
    val lastPublish: LastPublish?,
    /** 接続はしているが購読を拒否された理由(例: auth-required)。 */
    val refusal: String? = null,
) {
    sealed interface LastPublish {
        data object Pending : LastPublish
        data object Succeeded : LastPublish
        data class Failed(val reason: String) : LastPublish
    }
}

internal object ChannelRelayPresentation {
    private const val MAX_HOSTS_IN_HEADER = 2

    /** ヘッダー2行目(FR-09)。推奨リレーの host 一覧、多ければ件数、無ければユーザーリレー使用中。 */
    fun headerSummary(context: ChannelRelayContext): String {
        val recommended = context.recommendedRelays
        return when {
            recommended.isEmpty() -> "ユーザーリレーを使用中"
            recommended.size <= MAX_HOSTS_IN_HEADER -> recommended.joinToString("・") { it.relayHost() }
            else -> "${recommended.size} relays"
        }
    }

    /** 投稿欄上の案内(FR-10)。送信中は進捗、一部失敗は件数、それ以外は予定の送信先件数。 */
    fun composerSummary(context: ChannelRelayContext, publishState: ChannelPublishUiState): String {
        val done = publishState.succeeded.size + publishState.failed.size
        return when (publishState.phase) {
            ChannelPublishUiState.Phase.Sending ->
                if (publishState.targets.isEmpty()) "送信中…" else "送信中 $done/${publishState.targets.size}"
            ChannelPublishUiState.Phase.PartialSuccess -> publishState.summary ?: ""
            else -> if (context.writeRelays.isEmpty()) {
                "投稿先: あなたの書き込みリレー"
            } else {
                "投稿先: ${context.writeRelays.size} relays"
            }
        }
    }

    /** 推奨リレー → その他の閲覧先 → その他の投稿先の順に並べる。 */
    fun rows(
        context: ChannelRelayContext,
        connectionStates: Map<String, RelayConnectionState>,
        publishState: ChannelPublishUiState,
        refusals: Map<String, String> = emptyMap(),
    ): List<ChannelRelayRow> {
        val urls = linkedSetOf<String>().apply {
            addAll(context.recommendedRelays)
            addAll(context.readRelays)
            addAll(context.writeRelays)
            addAll(publishState.targets)
        }
        val recommended = context.recommendedRelays.toSet()
        return urls.map { url ->
            val read = url in context.readRelays
            ChannelRelayRow(
                url = url,
                isRecommended = url in recommended,
                usedForRead = read,
                usedForWrite = url in context.writeRelays,
                connection = if (read) connectionStates[url] ?: RelayConnectionState.Connecting else null,
                refusal = refusals[url]?.takeIf { read },
                lastPublish = when {
                    publishState.phase == ChannelPublishUiState.Phase.Idle || url !in publishState.targets -> null
                    url in publishState.succeeded -> ChannelRelayRow.LastPublish.Succeeded
                    url in publishState.failed -> ChannelRelayRow.LastPublish.Failed(publishState.failed.getValue(url))
                    else -> ChannelRelayRow.LastPublish.Pending
                },
            )
        }
    }
}
