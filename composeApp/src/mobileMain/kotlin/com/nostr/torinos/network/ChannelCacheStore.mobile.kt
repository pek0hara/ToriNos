package com.nostr.torinos.network

import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.cache.CachedChannelMessageEntity
import com.nostr.torinos.network.cache.CachedChannelMessageRelayEntity
import com.nostr.torinos.network.cache.ChannelReadStateEntity
import com.nostr.torinos.network.cache.createChannelCacheDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

actual object ChannelCacheStore {
    private val json = Json { encodeDefaults = true }
    private val dao by lazy { createChannelCacheDatabase().channelCacheDao() }

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

    actual suspend fun upsertMessage(relayUrl: String, event: NostrEvent, channelId: String) {
        dao.insertMessage(
            CachedChannelMessageEntity(
                channelId = channelId,
                eventId = event.id,
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                content = event.content,
                rawJson = json.encodeToString(NostrEvent.serializer(), event),
            )
        )
        dao.insertMessageRelay(CachedChannelMessageRelayEntity(relayUrl = relayUrl, eventId = event.id))
        dao.upsertChannelRelay(relayUrl = relayUrl, channelId = channelId, seenAt = event.createdAt)
    }

    actual suspend fun deleteChannel(relayUrl: String, channelId: String) {
        dao.deleteMessageRelays(channelId)
        dao.deleteMessages(channelId)
        dao.deleteReadState(channelId)
        dao.deleteChannelRelays(channelId)
        dao.deleteChannel(channelId)
    }

    actual suspend fun markRead(relayUrl: String, channelId: String, readAt: Long) {
        dao.upsertReadState(
            ChannelReadStateEntity(
                channelId = channelId,
                lastReadAt = readAt,
            )
        )
    }

    actual suspend fun saveScrollPosition(relayUrl: String, channelId: String, messageId: String) {
        dao.upsertScrollPosition(channelId, messageId)
    }

    actual suspend fun getScrollPosition(relayUrl: String, channelId: String): String? =
        dao.getScrollPosition(channelId)

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
        dao.getDistinctRelayUrls().forEach { relayUrl ->
            dao.pruneMessagesByRelay(relayUrl, maxMessages)
        }
        dao.deleteOrphanMessages()
    }
}
