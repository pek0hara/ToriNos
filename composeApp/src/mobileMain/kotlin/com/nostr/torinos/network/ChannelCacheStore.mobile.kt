package com.nostr.torinos.network

import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.cache.CachedChannelMessageEntity
import com.nostr.torinos.network.cache.CachedChannelMessageRelayEntity
import com.nostr.torinos.network.cache.CachedChannelRecommendedRelayEntity
import com.nostr.torinos.network.cache.ChannelMessageBundle
import com.nostr.torinos.network.cache.createChannelCacheDatabase
import com.nostr.torinos.util.cacheTraceLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

actual object ChannelCacheStore {
    private val json = Json { encodeDefaults = true }
    private val dao by lazy { createChannelCacheDatabase().channelCacheDao() }

    /** 非お気に入りチャンネル1件が保持できるメッセージ数の初期値。実機計測後に見直す。 */
    private const val NON_FAVORITE_CHANNEL_MESSAGE_LIMIT = 2_000

    /** お気に入りチャンネルは無制限にせず、非お気に入りより高い上限だけを設定する。実機計測後に見直す。 */
    private const val FAVORITE_CHANNEL_MESSAGE_LIMIT = 20_000

    actual fun observeChannels(relayUrl: String): Flow<List<CachedChannelSummary>> =
        dao.observeChannels(relayUrl).map { rows ->
            rows.map { row ->
                CachedChannelSummary(
                    relayUrl = row.relayUrl,
                    channelId = row.channelId,
                    name = row.name,
                    about = row.about,
                    picture = row.picture,
                    ownerPubkey = row.ownerPubkey,
                    createdAt = row.createdAt,
                    latestMessageId = row.latestMessageId,
                    latestMessageCreatedAt = row.latestMessageCreatedAt,
                    latestMessageAuthorPubkey = row.latestMessageAuthorPubkey,
                    latestMessagePreview = row.latestMessagePreview,
                    unreadCount = row.unreadCount,
                    hasBeenOpened = row.hasBeenOpened,
                    isFavorite = row.isFavorite,
                )
            }
        }

    actual suspend fun getLastReadAt(relayUrl: String, channelId: String): Long? =
        dao.getLastReadAt(channelId)

    actual suspend fun getMessages(relayUrl: String, channelId: String, limit: Int): List<NostrEvent> =
        dao.getMessages(channelId, limit)
            .mapNotNull { raw ->
                runCatching { json.decodeFromString(NostrEvent.serializer(), raw) }.getOrNull()
            }

    actual suspend fun upsertChannel(relayUrl: String, event: NostrEvent, meta: ChannelMeta) {
        dao.upsertChannel(
            channelId = event.id,
            name = meta.name,
            about = meta.about,
            picture = meta.picture,
            ownerPubkey = event.pubkey,
            createdAt = event.createdAt,
            updatedAt = event.createdAt,
        )
        dao.upsertChannelRelay(relayUrl = relayUrl, channelId = event.id, seenAt = event.createdAt)
    }

    actual suspend fun getChannelMetadata(channelId: String): CachedChannelMetadata? {
        val row = dao.getChannelMetadataRow(channelId) ?: return null
        val metadataEventId = row.metadataEventId ?: return null
        return CachedChannelMetadata(
            channelId = row.channelId,
            ownerPubkey = row.ownerPubkey,
            sourceEventId = metadataEventId,
            sourceKind = row.metadataKind,
            sourceCreatedAt = row.metadataCreatedAt,
            metadata = ChannelMeta(
                name = row.name,
                about = row.about,
                picture = row.picture,
                relays = dao.getRecommendedRelayUrls(channelId),
            ),
        )
    }

    actual suspend fun upsertChannelMetadata(
        channelCreateEvent: NostrEvent,
        effectiveEvent: NostrEvent,
        metadata: ChannelMeta,
        observedRelayUrl: String?,
    ) {
        dao.upsertChannelMetadata(
            channelId = channelCreateEvent.id,
            name = metadata.name,
            about = metadata.about,
            picture = metadata.picture,
            ownerPubkey = channelCreateEvent.pubkey,
            createdAt = channelCreateEvent.createdAt,
            metadataEventId = effectiveEvent.id,
            metadataKind = effectiveEvent.kind,
            metadataCreatedAt = effectiveEvent.createdAt,
            recommendedRelays = metadata.relays.mapIndexed { index, url ->
                CachedChannelRecommendedRelayEntity(
                    channelId = channelCreateEvent.id,
                    relayUrl = url,
                    position = index,
                )
            },
            observedRelayUrl = observedRelayUrl,
            observedAt = effectiveEvent.createdAt,
        )
    }

    actual suspend fun upsertMessage(relayUrl: String, event: NostrEvent, channelId: String) {
        val bundle = event.toMessageBundle(relayUrl, channelId)
        dao.upsertMessageBundle(
            message = bundle.message,
            relay = bundle.relay,
            relayUrl = bundle.relayUrl,
            channelId = bundle.channelId,
            seenAt = bundle.seenAt,
        )
    }

    actual suspend fun upsertMessages(relayUrl: String, events: List<NostrEvent>, channelId: String) {
        if (events.isEmpty()) return
        dao.upsertMessageBundles(events.map { it.toMessageBundle(relayUrl, channelId) })
        cacheTraceLog { "[ChannelCacheStore] upsertMessages channelId=$channelId count=${events.size} (1 transaction)" }
    }

    private fun NostrEvent.toMessageBundle(relayUrl: String, channelId: String) = ChannelMessageBundle(
        message = CachedChannelMessageEntity(
            channelId = channelId,
            eventId = id,
            pubkey = pubkey,
            createdAt = createdAt,
            content = content,
            rawJson = json.encodeToString(NostrEvent.serializer(), this),
        ),
        relay = CachedChannelMessageRelayEntity(relayUrl = relayUrl, eventId = id),
        relayUrl = relayUrl,
        channelId = channelId,
        seenAt = createdAt,
    )

    internal actual suspend fun deleteMessage(messageId: String) {
        dao.deleteMessageRelaysForMessage(messageId)
        dao.deleteMessage(messageId)
    }

    actual suspend fun deleteChannel(relayUrl: String, channelId: String) {
        dao.deleteMessageRelays(channelId)
        dao.deleteMessages(channelId)
        dao.deleteReadState(channelId)
        dao.deleteChannelRelays(channelId)
        dao.deleteChannel(channelId)
    }

    actual suspend fun markRead(relayUrl: String, channelId: String, readAt: Long) {
        dao.markRead(channelId, readAt)
    }

    actual suspend fun saveReadingPosition(relayUrl: String, channelId: String, position: ChannelReadingPosition) {
        dao.upsertScrollPosition(channelId, position.messageId, position.createdAt, position.scrollOffset)
    }

    actual suspend fun getReadingPosition(relayUrl: String, channelId: String): ChannelReadingPosition? {
        val state = dao.getReadingState(channelId) ?: return null
        return state.lastScrolledMessageId?.let {
            ChannelReadingPosition(it, state.lastScrolledCreatedAt, state.lastScrolledOffset)
        }
    }

    actual suspend fun getMessage(channelId: String, messageId: String): NostrEvent? =
        dao.getMessage(channelId, messageId)?.let { raw ->
            runCatching { json.decodeFromString(NostrEvent.serializer(), raw) }.getOrNull()
        }

    actual suspend fun setFavorite(relayUrl: String, channelId: String, isFavorite: Boolean) {
        dao.setFavorite(channelId, isFavorite)
    }

    actual suspend fun deleteNonFavorites(relayUrl: String) {
        dao.deleteNonFavoriteMessages(relayUrl)
        dao.deleteOrphanMessages()
        dao.deleteNonFavoriteReadStates(relayUrl)
        dao.deleteNonFavoriteChannels(relayUrl)
        dao.deleteOrphanChannels()
    }

    actual suspend fun prune(maxMessages: Int) {
        val countBefore = dao.countMessages()
        val favoriteChannelIds = dao.getAllFavoriteChannelIds().toSet()
        dao.getChannelIdsWithMessages().forEach { channelId ->
            val perChannelLimit = if (channelId in favoriteChannelIds) {
                FAVORITE_CHANNEL_MESSAGE_LIMIT
            } else {
                NON_FAVORITE_CHANNEL_MESSAGE_LIMIT
            }
            dao.pruneMessagesByChannel(channelId, perChannelLimit)
        }
        dao.pruneMessagesGlobally(maxMessages)
        dao.deleteOrphanMessageRelays()
        val countAfter = dao.countMessages()
        cacheTraceLog {
            "[ChannelCacheStore] prune favoriteChannels=${favoriteChannelIds.size} " +
                "messages=$countBefore->$countAfter (removed=${countBefore - countAfter})"
        }
    }
}
