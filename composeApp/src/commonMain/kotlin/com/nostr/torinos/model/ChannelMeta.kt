package com.nostr.torinos.model

import com.nostr.torinos.network.normalizeRelayUrls
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChannelMeta(
    val name: String = "",
    val about: String = "",
    val picture: String = "",
    val relays: List<String> = emptyList(),
)

data class EffectiveChannelMetadata(
    val channelId: String,
    val ownerPubkey: String,
    val sourceEventId: String,
    val sourceKind: Int,
    val updatedAt: Long,
    val metadata: ChannelMeta,
)

internal data class ChannelMetadataResolution(
    val channelCreateEvent: NostrEvent,
    val effectiveEvent: NostrEvent,
    val metadata: ChannelMeta,
    val ignoredEventIds: Set<String>,
) {
    val effectiveMetadata: EffectiveChannelMetadata = EffectiveChannelMetadata(
        channelId = channelCreateEvent.id,
        ownerPubkey = channelCreateEvent.pubkey,
        sourceEventId = effectiveEvent.id,
        sourceKind = effectiveEvent.kind,
        updatedAt = effectiveEvent.createdAt,
        metadata = metadata,
    )
}

internal object ChannelMetadataResolver {
    fun acceptsCandidate(
        channelId: String,
        ownerPubkey: String,
        event: NostrEvent,
    ): Boolean = when (event.kind) {
        40 -> event.id == channelId
        41 -> event.isValidChannelMetadataUpdate(channelId, ownerPubkey)
        else -> false
    }

    fun resolve(
        channelId: String,
        createCandidates: Collection<NostrEvent>,
        updateCandidates: Collection<NostrEvent>,
    ): ChannelMetadataResolution? {
        // content がJSONとしてパースできない作成イベントであっても、オーナー特定のためにイベント自体は必要。
        // メタデータはこの後updateから拾えることがあるため、ここではまだ諦めない。
        val create = createCandidates
            .asSequence()
            .filter { it.kind == 40 && it.id == channelId }
            .latestByCreatedAt { it }
            ?: return null
        val createMeta = create.toChannelMeta()

        val updateWithMeta = updateCandidates
            .asSequence()
            .filter { event -> acceptsCandidate(channelId, create.pubkey, event) }
            .mapNotNull { event -> event.toChannelMeta()?.let { event to it } }
            .latestByCreatedAt { it.first }

        val effective = updateWithMeta
            ?: createMeta?.let { create to it }
            ?: return null
        val acceptedIds = buildSet {
            add(create.id)
            add(effective.first.id)
        }
        val ignoredIds = (createCandidates.asSequence() + updateCandidates.asSequence())
            .map { it.id }
            .filterNot { it in acceptedIds }
            .toSet()
        return ChannelMetadataResolution(
            channelCreateEvent = create,
            effectiveEvent = effective.first,
            metadata = effective.second,
            ignoredEventIds = ignoredIds,
        )
    }
}

private val channelJson = Json { ignoreUnknownKeys = true }

/** kind:40 イベントの content から ChannelMeta をパース */
fun NostrEvent.toChannelMeta(): ChannelMeta? = try {
    channelJson.decodeFromString<ChannelMeta>(content).let { meta ->
        meta.copy(relays = normalizeRelayUrls(meta.relays))
    }
} catch (_: Exception) {
    null
}

private fun NostrEvent.isValidChannelMetadataUpdate(channelId: String, ownerPubkey: String): Boolean =
    kind == 41 && pubkey == ownerPubkey && tags.any { tag ->
        tag.firstOrNull() == "e" &&
            tag.getOrNull(1) == channelId &&
            tag.getOrNull(3).let { marker -> marker.isNullOrBlank() || marker == "root" }
    }

/**
 * created_at が同値の場合はid最小のイベントを勝者とする（NIP-01慣習）。
 * ProfileCache/RelayListEventCacheのisNewerThanと同じタイブレーク規則。
 */
private fun NostrEvent.isNewerThan(other: NostrEvent): Boolean =
    createdAt > other.createdAt || (createdAt == other.createdAt && id < other.id)

private fun <T> Sequence<T>.latestByCreatedAt(eventOf: (T) -> NostrEvent): T? {
    var latest: T? = null
    for (item in this) {
        val current = latest
        if (current == null || eventOf(item).isNewerThan(eventOf(current))) {
            latest = item
        }
    }
    return latest
}
