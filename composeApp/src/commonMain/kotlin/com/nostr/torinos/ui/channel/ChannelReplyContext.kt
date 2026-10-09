package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReplyEventReference
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.network.ChannelLocalStore
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.normalizeRelayUrl
import com.nostr.torinos.network.normalizeRelayUrls
import com.nostr.torinos.network.readableRelayUrls
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

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

/** スレッドを開いても、チャンネルの推奨リレーと閲覧先を引き継ぐ。 */
internal suspend fun channelThreadReadTarget(channelId: String): RelayTarget {
    val hint = RelayStore.selectedChannelRelayUrl.value
    val entries = RelayStore.entries.value
    val local = try {
        withTimeoutOrNull(10_000) { ChannelLocalStore.get(channelId) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        logException("ChannelReplyContext", error, "Could not read channel thread relay metadata")
        null
    }
    return channelThreadReadTarget(local?.meta?.relays.orEmpty(), local?.observedRelays.orEmpty(), hint, entries)
}

internal fun channelThreadReadTarget(
    recommendedRelays: List<String>,
    observedRelays: List<String>,
    navigationRelayHint: String?,
    relayEntries: List<RelayEntry>,
): RelayTarget {
    // 検索などからも開くため、選択中のチャンネルリレーだけに読み取り先を絞らない。
    val reads = normalizeRelayUrls(
        recommendedRelays + observedRelays + listOfNotNull(navigationRelayHint) + readableRelayUrls(relayEntries),
        limit = Int.MAX_VALUE,
    ).toSet()
    return ChannelRelayPlanner.readTarget(reads, null)
}
