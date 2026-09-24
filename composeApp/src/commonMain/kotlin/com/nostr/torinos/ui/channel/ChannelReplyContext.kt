package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReplyEventReference
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.network.ChannelLocalStore
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.normalizeRelayUrl

/**
 * 通常投稿画面でチャンネルへ返信するときの送信先と relay hint(第16.14節)。
 * [initialRelayUrls] は投稿画面の送信先の初期選択、[recommendedRelayUrls] は外したときの警告に使う。
 */
data class ComposerRelayContext(
    val initialRelayUrls: Set<String>,
    val recommendedRelayUrls: List<String>,
    val primaryHint: String?,
)

internal object ChannelReplyContextBuilder {
    /** チャンネル画面と同じ規則(推奨リレー + ユーザーの書き込みリレー)で作る。 */
    fun build(recommendedRelays: List<String>, relayEntries: List<RelayEntry>): ComposerRelayContext {
        val context = ChannelRelayPlanner.context(recommendedRelays, null, relayEntries)
        return ComposerRelayContext(
            initialRelayUrls = context.writeRelays,
            recommendedRelayUrls = context.recommendedRelays,
            primaryHint = context.primaryHint,
        )
    }

    /**
     * 返信先の relay hint は、観測元リレー → チャンネルの primary hint の順(第16.8節)。
     * 観測元のリレー名は正規化してから使う。
     */
    fun replyTarget(
        event: NostrEvent,
        channelId: String,
        parentRelayHint: String?,
        context: ComposerRelayContext,
    ): ReplyTarget.Channel? {
        if (event.kind != 42) return null
        return ReplyTarget.Channel(
            channelId = channelId,
            parent = ReplyEventReference(
                id = event.id,
                kind = event.kind,
                pubkey = event.pubkey,
                relayUrl = parentRelayHint?.let(::normalizeRelayUrl) ?: context.primaryHint,
            ),
            channelRelayUrl = context.primaryHint,
        )
    }
}

/** 保存済みの実効メタデータ(チャンネル画面が更新する)から返信用の context を作る。画面外からの返信でも使える。 */
internal suspend fun channelComposerRelayContext(channelId: String): ComposerRelayContext =
    ChannelReplyContextBuilder.build(
        recommendedRelays = ChannelLocalStore.get(channelId)?.meta?.relays.orEmpty(),
        relayEntries = RelayStore.entries.value,
    )
