package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatListCacheTest {
    private fun message(id: String, createdAt: Long, content: String = "hi") =
        NostrEvent(id, "a".repeat(64), createdAt, GROUP_CHAT_KIND, listOf(listOf("h", "g1")), content, "s")

    private val metadata = GroupMetadata(
        groupId = "g1", name = "general", picture = "", about = "", isPrivate = false, isRestricted = false,
        isHidden = false, isClosed = false, supportedKinds = null, createdAt = 1, channelType = "stream", labels = listOf("x"),
    )

    @Test
    fun roundTripsRows() {
        val snapshot = ChatListSnapshot(
            listOf(CachedChatRow("wss://buzz.example", "g1", buzzJoined = true, metadata = metadata, lastMessage = message("m1", 100), editedBody = "edited", threadRoot = message("r1", 90, "root"))),
        )
        val decoded = ChatListCache.decodeChatListSnapshot(ChatListCache.encodeChatListSnapshot(snapshot))
        assertEquals(snapshot, decoded)
    }

    @Test
    fun trimsLongBodies() {
        val long = "x".repeat(5_000)
        val snapshot = ChatListSnapshot(listOf(CachedChatRow("wss://r", "g1", lastMessage = message("m1", 1, long), editedBody = long, threadRoot = message("r1", 1, long))))
        val decoded = ChatListCache.decodeChatListSnapshot(ChatListCache.encodeChatListSnapshot(snapshot))
        assertTrue(decoded.rows.single().threadRoot!!.content.length < 1_000)
        assertTrue(decoded.rows.single().lastMessage!!.content.length < 1_000)
        assertTrue(decoded.rows.single().editedBody!!.length < 1_000)
    }

    @Test
    fun brokenOrMissingStorageIsEmpty() {
        assertEquals(ChatListSnapshot(), ChatListCache.decodeChatListSnapshot(null))
        assertEquals(ChatListSnapshot(), ChatListCache.decodeChatListSnapshot("{broken"))
    }
}
