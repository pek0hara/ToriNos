package com.nostr.torinos.network

import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual object ChannelCacheStore {
    actual fun observeChannels(relayUrl: String): Flow<List<CachedChannelSummary>> = flowOf(emptyList())
    actual suspend fun getLastReadAt(relayUrl: String, channelId: String): Long? = null
    actual suspend fun getMessages(relayUrl: String, channelId: String, limit: Int): List<NostrEvent> = emptyList()
    actual suspend fun upsertChannel(relayUrl: String, event: NostrEvent, meta: ChannelMeta) = Unit
    actual suspend fun upsertMessage(relayUrl: String, event: NostrEvent, channelId: String) = Unit
    actual suspend fun deleteChannel(relayUrl: String, channelId: String) = Unit
    actual suspend fun markRead(relayUrl: String, channelId: String, readAt: Long) = Unit
    actual suspend fun saveScrollPosition(relayUrl: String, channelId: String, messageId: String) = Unit
    actual suspend fun getScrollPosition(relayUrl: String, channelId: String): String? = null
    actual suspend fun setFavorite(relayUrl: String, channelId: String, isFavorite: Boolean) = Unit
    actual suspend fun deleteNonFavorites(relayUrl: String) = Unit
    actual suspend fun prune(maxMessages: Int) = Unit
}
