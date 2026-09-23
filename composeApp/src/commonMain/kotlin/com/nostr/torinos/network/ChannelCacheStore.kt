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

data class ChannelReadingPosition(
    val messageId: String,
    val createdAt: Long?,
    val scrollOffset: Int = 0,
)

/** チャンネルの実効メタデータの取得元(第16.12節)。観測元リレー(channel_relays)とは別概念。 */
data class CachedChannelMetadata(
    val channelId: String,
    val ownerPubkey: String,
    val sourceEventId: String,
    val sourceKind: Int,
    val sourceCreatedAt: Long,
    val metadata: ChannelMeta,
)

expect object ChannelCacheStore {
    fun observeChannels(relayUrl: String): Flow<List<CachedChannelSummary>>
    suspend fun getLastReadAt(relayUrl: String, channelId: String): Long?
    suspend fun getMessages(relayUrl: String, channelId: String, limit: Int = 200): List<NostrEvent>
    suspend fun upsertChannel(relayUrl: String, event: NostrEvent, meta: ChannelMeta)
    suspend fun getChannelMetadata(channelId: String): CachedChannelMetadata?
    /**
     * 実効メタデータ、推奨リレー(全置換)、任意の観測元リレーを1トランザクションで更新する。
     * kind 41のevent IDをchannelIdとして保存しないよう、[channelCreateEvent]と[effectiveEvent]を分けて渡す。
     */
    suspend fun upsertChannelMetadata(
        channelCreateEvent: NostrEvent,
        effectiveEvent: NostrEvent,
        metadata: ChannelMeta,
        observedRelayUrl: String? = null,
    )
    suspend fun upsertMessage(relayUrl: String, event: NostrEvent, channelId: String)
    /** ページ単位の履歴取得で、複数イベントを1トランザクションで保存する。 */
    suspend fun upsertMessages(relayUrl: String, events: List<NostrEvent>, channelId: String)
    internal suspend fun deleteMessage(messageId: String)
    suspend fun markRead(relayUrl: String, channelId: String, readAt: Long)
    suspend fun saveReadingPosition(relayUrl: String, channelId: String, position: ChannelReadingPosition)
    suspend fun getReadingPosition(relayUrl: String, channelId: String): ChannelReadingPosition?
    suspend fun getMessage(channelId: String, messageId: String): NostrEvent?
    suspend fun deleteChannel(relayUrl: String, channelId: String)
    suspend fun setFavorite(relayUrl: String, channelId: String, isFavorite: Boolean)
    suspend fun deleteNonFavorites(relayUrl: String)
    /** [maxMessages]は特定リレーではなく、全チャンネル合計のメッセージ件数に対する上限。 */
    suspend fun prune(maxMessages: Int = 50_000)
}
