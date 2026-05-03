package com.nostr.torinos.network.cache

import androidx.room.Dao
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "channels",
    primaryKeys = ["relayUrl", "channelId"],
)
data class CachedChannelEntity(
    val relayUrl: String,
    val channelId: String,
    val name: String,
    val about: String,
    val picture: String,
    val ownerPubkey: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "channel_messages",
    primaryKeys = ["relayUrl", "eventId"],
)
data class CachedChannelMessageEntity(
    val relayUrl: String,
    val channelId: String,
    val eventId: String,
    val pubkey: String,
    val createdAt: Long,
    val content: String,
    val rawJson: String,
)

@Entity(
    tableName = "channel_read_states",
    primaryKeys = ["relayUrl", "channelId"],
)
data class ChannelReadStateEntity(
    val relayUrl: String,
    val channelId: String,
    val lastReadAt: Long,
)

data class CachedChannelSummaryRow(
    val relayUrl: String,
    val channelId: String,
    val name: String,
    val about: String,
    val picture: String,
    val ownerPubkey: String,
    val createdAt: Long,
    val latestMessageId: String?,
    val latestMessageCreatedAt: Long?,
    val latestMessagePreview: String?,
    val unreadCount: Int,
)

@Dao
interface ChannelCacheDao {
    @Query(
        """
        SELECT
            c.relayUrl AS relayUrl,
            c.channelId AS channelId,
            c.name AS name,
            c.about AS about,
            c.picture AS picture,
            c.ownerPubkey AS ownerPubkey,
            c.createdAt AS createdAt,
            latest.eventId AS latestMessageId,
            latest.createdAt AS latestMessageCreatedAt,
            latest.content AS latestMessagePreview,
            (
                SELECT COUNT(*)
                FROM channel_messages unread
                WHERE unread.relayUrl = c.relayUrl
                  AND unread.channelId = c.channelId
                  AND unread.createdAt > COALESCE(r.lastReadAt, 0)
            ) AS unreadCount
        FROM channels c
        LEFT JOIN channel_read_states r
            ON r.relayUrl = c.relayUrl AND r.channelId = c.channelId
        LEFT JOIN channel_messages latest
            ON latest.relayUrl = c.relayUrl
           AND latest.eventId = (
                SELECT m.eventId
                FROM channel_messages m
                WHERE m.relayUrl = c.relayUrl AND m.channelId = c.channelId
                ORDER BY m.createdAt DESC, m.eventId DESC
                LIMIT 1
           )
        WHERE c.relayUrl = :relayUrl
        ORDER BY COALESCE(latest.createdAt, c.createdAt) DESC
        """
    )
    fun observeChannels(relayUrl: String): Flow<List<CachedChannelSummaryRow>>

    @Query(
        """
        SELECT lastReadAt
        FROM channel_read_states
        WHERE relayUrl = :relayUrl AND channelId = :channelId
        LIMIT 1
        """
    )
    suspend fun getLastReadAt(relayUrl: String, channelId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: CachedChannelEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: CachedChannelMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadState(state: ChannelReadStateEntity)

    @Query("SELECT DISTINCT relayUrl FROM channel_messages")
    suspend fun getDistinctRelayUrls(): List<String>

    @Query(
        """
        DELETE FROM channel_messages
        WHERE relayUrl = :relayUrl AND eventId NOT IN (
            SELECT eventId
            FROM channel_messages
            WHERE relayUrl = :relayUrl
            ORDER BY createdAt DESC
            LIMIT :maxMessages
        )
        """
    )
    suspend fun pruneMessagesByRelay(relayUrl: String, maxMessages: Int)
}

@Database(
    entities = [
        CachedChannelEntity::class,
        CachedChannelMessageEntity::class,
        ChannelReadStateEntity::class,
    ],
    version = 1,
)
@ConstructedBy(ChannelCacheDatabaseConstructor::class)
abstract class ChannelCacheDatabase : RoomDatabase() {
    abstract fun channelCacheDao(): ChannelCacheDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object ChannelCacheDatabaseConstructor : RoomDatabaseConstructor<ChannelCacheDatabase> {
    override fun initialize(): ChannelCacheDatabase
}
