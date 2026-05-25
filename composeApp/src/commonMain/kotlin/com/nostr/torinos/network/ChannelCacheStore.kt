package com.nostr.torinos.network

import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.flow.Flow

data class CachedChannelSummary(
    val relayUrl: String,
    val channelId: String,
    val name: String,
    val about: String,
    val picture: String,
    val ownerPubkey: String,
    val createdAt: Long,
    val latestMessageId: String?,
    val latestMessageCreatedAt: Long?,
    val latestMessageAuthorPubkey: String?,
    val latestMessagePreview: String?,
    val unreadCount: Int,
    val hasBeenOpened: Boolean,
    val isFavorite: Boolean = false,
) {
    val hasUnread: Boolean get() = unreadCount > 0
}

expect object ChannelCacheStore {
    fun observeChannels(relayUrl: String): Flow<List<CachedChannelSummary>>
    suspend fun getLastReadAt(relayUrl: String, channelId: String): Long?
    suspend fun getMessages(relayUrl: String, channelId: String, limit: Int = 200): List<NostrEvent>
    suspend fun upsertChannel(relayUrl: String, event: NostrEvent, meta: ChannelMeta)
    suspend fun upsertMessage(relayUrl: String, event: NostrEvent, channelId: String)
    suspend fun markRead(relayUrl: String, channelId: String, readAt: Long)
    suspend fun saveScrollPosition(relayUrl: String, channelId: String, messageId: String)
    suspend fun getScrollPosition(relayUrl: String, channelId: String): String?
    suspend fun deleteChannel(relayUrl: String, channelId: String)
    suspend fun setFavorite(relayUrl: String, channelId: String, isFavorite: Boolean)
    suspend fun deleteNonFavorites(relayUrl: String)
    suspend fun prune(maxMessages: Int = 50_000)
}
