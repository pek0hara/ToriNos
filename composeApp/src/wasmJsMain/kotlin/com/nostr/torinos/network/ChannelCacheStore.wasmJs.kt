package com.nostr.torinos.network

import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual object ChannelCacheStore {
    actual fun observeChannels(relayUrl: String): Flow<List<CachedChannelSummary>> = flowOf(emptyList())
    actual suspend fun getLastReadAt(relayUrl: String, channelId: String): Long? = null
    actual suspend fun upsertChannel(relayUrl: String, event: NostrEvent, meta: ChannelMeta) = Unit
    actual suspend fun upsertMessage(relayUrl: String, event: NostrEvent, channelId: String) = Unit
    actual suspend fun markRead(relayUrl: String, channelId: String, readAt: Long) = Unit
    actual suspend fun prune(maxMessages: Int) = Unit
}
