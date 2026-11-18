package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.LocalSettingsStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * チャット一覧の前回の中身。次に開いたとき、リレーの答えを待たずに前回の並びで出すために使う。
 * 読み込みが終わったら、リレーの答えで上書きする。
 */
@Serializable
internal data class ChatListSnapshot(val rows: List<CachedChatRow> = emptyList())

@Serializable
internal data class CachedChatRow(
    val relayUrl: String,
    val groupId: String,
    /** Buzz の参加中のチャンネル（参加中かどうかはリレーの 39002 で決まるので、ここにも残す）。 */
    val buzzJoined: Boolean = false,
    val metadata: GroupMetadata? = null,
    val lastMessage: NostrEvent? = null,
    val editedBody: String? = null,
    /** [lastMessage] が Buzz のスレッドの返信のときの、スレッドのルート。 */
    val threadRoot: NostrEvent? = null,
    val threadRootEditedBody: String? = null,
) {
    val ref: GroupRef get() = GroupRef(relayUrl, groupId)
}

internal object ChatListCache {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(pubkey: String): ChatListSnapshot =
        decodeChatListSnapshot(LocalSettingsStorage.getString(key(pubkey)))

    suspend fun save(pubkey: String, snapshot: ChatListSnapshot) {
        LocalSettingsStorage.putString(key(pubkey), encodeChatListSnapshot(snapshot))
    }

    internal fun encodeChatListSnapshot(snapshot: ChatListSnapshot): String =
        json.encodeToString(ChatListSnapshot.serializer(), snapshot.trimmed())

    internal fun decodeChatListSnapshot(stored: String?): ChatListSnapshot =
        stored?.let { runCatching { json.decodeFromString(ChatListSnapshot.serializer(), it) }.getOrNull() } ?: ChatListSnapshot()

    private fun key(pubkey: String) = "chat.listSnapshot.$pubkey"
}

/** 一覧の1行目に出すだけなので、本文は短く切って保存する（長い Markdown や画像の URL で大きくしない）。 */
private const val CACHED_BODY_MAX = 600
private const val CACHED_ROWS_MAX = 200

private fun ChatListSnapshot.trimmed(): ChatListSnapshot = ChatListSnapshot(
    rows.take(CACHED_ROWS_MAX).map { row ->
        row.copy(
            lastMessage = row.lastMessage?.let { it.copy(content = it.content.take(CACHED_BODY_MAX)) },
            editedBody = row.editedBody?.take(CACHED_BODY_MAX),
            threadRoot = row.threadRoot?.let { it.copy(content = it.content.take(CACHED_BODY_MAX)) },
            threadRootEditedBody = row.threadRootEditedBody?.take(CACHED_BODY_MAX),
            metadata = row.metadata?.let { it.copy(about = it.about.take(CACHED_BODY_MAX)) },
        )
    },
)
