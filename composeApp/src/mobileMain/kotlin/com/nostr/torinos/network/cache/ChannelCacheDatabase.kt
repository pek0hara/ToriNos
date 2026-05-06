package com.nostr.torinos.network.cache

import androidx.room.Dao
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE channel_read_states ADD COLUMN lastScrolledMessageId TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE channels ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE channels RENAME TO channels_v3")
        connection.execSQL("ALTER TABLE channel_messages RENAME TO channel_messages_v3")
        connection.execSQL("ALTER TABLE channel_read_states RENAME TO channel_read_states_v3")

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS channels (
                channelId TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                about TEXT NOT NULL,
                picture TEXT NOT NULL,
                ownerPubkey TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                isFavorite INTEGER NOT NULL
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS channel_relays (
                relayUrl TEXT NOT NULL,
                channelId TEXT NOT NULL,
                firstSeenAt INTEGER NOT NULL,
                lastSeenAt INTEGER NOT NULL,
                PRIMARY KEY(relayUrl, channelId)
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS channel_messages (
                eventId TEXT NOT NULL PRIMARY KEY,
                channelId TEXT NOT NULL,
                pubkey TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                content TEXT NOT NULL,
                rawJson TEXT NOT NULL
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS channel_message_relays (
                relayUrl TEXT NOT NULL,
                eventId TEXT NOT NULL,
                PRIMARY KEY(relayUrl, eventId)
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS channel_read_states (
                channelId TEXT NOT NULL PRIMARY KEY,
                lastReadAt INTEGER NOT NULL,
                lastScrolledMessageId TEXT
            )
            """.trimIndent()
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_channel_relays_channelId ON channel_relays(channelId)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_channel_messages_channelId ON channel_messages(channelId)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_channel_message_relays_eventId ON channel_message_relays(eventId)")

        connection.execSQL(
            """
            INSERT OR REPLACE INTO channels
                (channelId, name, about, picture, ownerPubkey, createdAt, updatedAt, isFavorite)
            SELECT
                channelId,
                name,
                about,
                picture,
                ownerPubkey,
                MIN(createdAt),
                MAX(updatedAt),
                MAX(isFavorite)
            FROM channels_v3
            GROUP BY channelId
            """.trimIndent()
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO channel_relays (relayUrl, channelId, firstSeenAt, lastSeenAt)
            SELECT relayUrl, channelId, MIN(createdAt), MAX(updatedAt)
            FROM channels_v3
            GROUP BY relayUrl, channelId
            """.trimIndent()
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO channel_messages
                (eventId, channelId, pubkey, createdAt, content, rawJson)
            SELECT eventId, channelId, pubkey, createdAt, content, rawJson
            FROM channel_messages_v3
            """.trimIndent()
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO channel_message_relays (relayUrl, eventId)
            SELECT relayUrl, eventId
            FROM channel_messages_v3
            """.trimIndent()
        )
        connection.execSQL(
            """
            INSERT OR REPLACE INTO channel_read_states
                (channelId, lastReadAt, lastScrolledMessageId)
            SELECT channelId, MAX(lastReadAt), MAX(lastScrolledMessageId)
            FROM channel_read_states_v3
            GROUP BY channelId
            """.trimIndent()
        )

        connection.execSQL("DROP TABLE channels_v3")
        connection.execSQL("DROP TABLE channel_messages_v3")
        connection.execSQL("DROP TABLE channel_read_states_v3")
    }
}

@Entity(
    tableName = "channels",
    primaryKeys = ["channelId"],
)
data class CachedChannelEntity(
    val channelId: String,
    val name: String,
    val about: String,
    val picture: String,
    val ownerPubkey: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean = false,
)

@Entity(
    tableName = "channel_relays",
    primaryKeys = ["relayUrl", "channelId"],
    indices = [Index("channelId")],
)
data class CachedChannelRelayEntity(
    val relayUrl: String,
    val channelId: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

@Entity(
    tableName = "channel_messages",
    primaryKeys = ["eventId"],
    indices = [Index("channelId")],
)
data class CachedChannelMessageEntity(
    val channelId: String,
    val eventId: String,
    val pubkey: String,
    val createdAt: Long,
    val content: String,
    val rawJson: String,
)

@Entity(
    tableName = "channel_message_relays",
    primaryKeys = ["relayUrl", "eventId"],
    indices = [Index("eventId")],
)
data class CachedChannelMessageRelayEntity(
    val relayUrl: String,
    val eventId: String,
)

@Entity(
    tableName = "channel_read_states",
    primaryKeys = ["channelId"],
)
data class ChannelReadStateEntity(
    val channelId: String,
    val lastReadAt: Long,
    val lastScrolledMessageId: String? = null,
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
    val hasBeenOpened: Boolean,
    val isFavorite: Boolean,
)

@Dao
interface ChannelCacheDao {
    @Query(
        """
        SELECT
            :relayUrl AS relayUrl,
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
                WHERE unread.channelId = c.channelId
                  AND unread.createdAt > COALESCE(r.lastReadAt, 0)
            ) AS unreadCount,
            CASE WHEN r.lastReadAt IS NOT NULL THEN 1 ELSE 0 END AS hasBeenOpened,
            c.isFavorite AS isFavorite
        FROM channels c
        LEFT JOIN channel_read_states r
            ON r.channelId = c.channelId
        LEFT JOIN channel_messages latest
            ON latest.eventId = (
                SELECT m.eventId
                FROM channel_messages m
                WHERE m.channelId = c.channelId
                ORDER BY m.createdAt DESC, m.eventId DESC
                LIMIT 1
           )
        WHERE EXISTS (
            SELECT 1
            FROM channel_relays cr
            WHERE cr.relayUrl = :relayUrl AND cr.channelId = c.channelId
        )
        ORDER BY c.isFavorite DESC, COALESCE(latest.createdAt, c.createdAt) DESC
        """
    )
    fun observeChannels(relayUrl: String): Flow<List<CachedChannelSummaryRow>>

    @Query(
        """
        SELECT lastReadAt
        FROM channel_read_states
        WHERE channelId = :channelId
        LIMIT 1
        """
    )
    suspend fun getLastReadAt(channelId: String): Long?

    @Query(
        """
        SELECT lastScrolledMessageId
        FROM channel_read_states
        WHERE channelId = :channelId
        LIMIT 1
        """
    )
    suspend fun getScrollPosition(channelId: String): String?

    @Query(
        """
        INSERT INTO channel_read_states (channelId, lastReadAt, lastScrolledMessageId)
        VALUES (:channelId, 0, :messageId)
        ON CONFLICT(channelId) DO UPDATE SET lastScrolledMessageId = :messageId
        """
    )
    suspend fun upsertScrollPosition(channelId: String, messageId: String)

    @Query(
        """
        INSERT INTO channels (channelId, name, about, picture, ownerPubkey, createdAt, updatedAt, isFavorite)
        VALUES (:channelId, :name, :about, :picture, :ownerPubkey, :createdAt, :updatedAt, 0)
        ON CONFLICT(channelId) DO UPDATE SET
            name = excluded.name,
            about = excluded.about,
            picture = excluded.picture,
            ownerPubkey = excluded.ownerPubkey,
            createdAt = excluded.createdAt,
            updatedAt = excluded.updatedAt
        """
    )
    suspend fun upsertChannel(
        channelId: String,
        name: String,
        about: String,
        picture: String,
        ownerPubkey: String,
        createdAt: Long,
        updatedAt: Long,
    )

    @Query(
        """
        INSERT INTO channel_relays (relayUrl, channelId, firstSeenAt, lastSeenAt)
        VALUES (:relayUrl, :channelId, :seenAt, :seenAt)
        ON CONFLICT(relayUrl, channelId) DO UPDATE SET lastSeenAt = MAX(lastSeenAt, :seenAt)
        """
    )
    suspend fun upsertChannelRelay(relayUrl: String, channelId: String, seenAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: CachedChannelMessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageRelay(relay: CachedChannelMessageRelayEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadState(state: ChannelReadStateEntity)

    @Query(
        """
        SELECT rawJson FROM channel_messages
        WHERE channelId = :channelId
        ORDER BY createdAt ASC
        LIMIT :limit
        """
    )
    suspend fun getMessages(channelId: String, limit: Int): List<String>

    @Query("DELETE FROM channels WHERE channelId = :channelId")
    suspend fun deleteChannel(channelId: String)

    @Query("DELETE FROM channel_messages WHERE channelId = :channelId")
    suspend fun deleteMessages(channelId: String)

    @Query("DELETE FROM channel_read_states WHERE channelId = :channelId")
    suspend fun deleteReadState(channelId: String)

    @Query("DELETE FROM channel_relays WHERE channelId = :channelId")
    suspend fun deleteChannelRelays(channelId: String)

    @Query(
        """
        DELETE FROM channel_message_relays
        WHERE eventId IN (
            SELECT eventId FROM channel_messages WHERE channelId = :channelId
        )
        """
    )
    suspend fun deleteMessageRelays(channelId: String)

    @Query("SELECT DISTINCT relayUrl FROM channel_message_relays")
    suspend fun getDistinctRelayUrls(): List<String>

    @Query(
        """
        DELETE FROM channel_message_relays
        WHERE relayUrl = :relayUrl AND eventId NOT IN (
            SELECT m.eventId
            FROM channel_messages m
            INNER JOIN channel_message_relays mr ON mr.eventId = m.eventId
            WHERE mr.relayUrl = :relayUrl
            ORDER BY createdAt DESC
            LIMIT :maxMessages
        )
        """
    )
    suspend fun pruneMessagesByRelay(relayUrl: String, maxMessages: Int)

    @Query("DELETE FROM channel_messages WHERE eventId NOT IN (SELECT eventId FROM channel_message_relays)")
    suspend fun deleteOrphanMessages()

    @Query("DELETE FROM channels WHERE channelId NOT IN (SELECT channelId FROM channel_relays)")
    suspend fun deleteOrphanChannels()

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE channelId = :channelId")
    suspend fun setFavorite(channelId: String, isFavorite: Boolean)

    @Query(
        """
        SELECT c.channelId
        FROM channels c
        INNER JOIN channel_relays cr ON cr.channelId = c.channelId
        WHERE cr.relayUrl = :relayUrl AND c.isFavorite = 1
        """
    )
    suspend fun getFavoriteChannelIds(relayUrl: String): List<String>

    @Query(
        """
        DELETE FROM channel_message_relays
        WHERE relayUrl = :relayUrl AND eventId IN (
            SELECT m.eventId
            FROM channel_messages m
            INNER JOIN channels c ON c.channelId = m.channelId
            WHERE c.isFavorite = 0
        )
        """
    )
    suspend fun deleteNonFavoriteMessages(relayUrl: String)

    @Query(
        """
        DELETE FROM channel_read_states
        WHERE channelId IN (
            SELECT cr.channelId
            FROM channel_relays cr
            INNER JOIN channels c ON c.channelId = cr.channelId
            WHERE cr.relayUrl = :relayUrl AND c.isFavorite = 0
        )
        """
    )
    suspend fun deleteNonFavoriteReadStates(relayUrl: String)

    @Query(
        """
        DELETE FROM channel_relays
        WHERE relayUrl = :relayUrl AND channelId IN (
            SELECT channelId FROM channels WHERE isFavorite = 0
        )
        """
    )
    suspend fun deleteNonFavoriteChannels(relayUrl: String)
}

@Database(
    entities = [
        CachedChannelEntity::class,
        CachedChannelRelayEntity::class,
        CachedChannelMessageEntity::class,
        CachedChannelMessageRelayEntity::class,
        ChannelReadStateEntity::class,
    ],
    version = 4,
)
@ConstructedBy(ChannelCacheDatabaseConstructor::class)
abstract class ChannelCacheDatabase : RoomDatabase() {
    abstract fun channelCacheDao(): ChannelCacheDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object ChannelCacheDatabaseConstructor : RoomDatabaseConstructor<ChannelCacheDatabase> {
    override fun initialize(): ChannelCacheDatabase
}
