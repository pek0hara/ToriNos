package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupRoomCacheTest {
    private val ref = GroupRef("wss://g.example", "g1")
    private fun event(id: String, createdAt: Long, kind: Int = GROUP_CHAT_KIND) =
        NostrEvent(id, "a".repeat(64), createdAt, kind, listOf(listOf("h", "g1")), "", "s")

    @Test
    fun remembersEventsPerRoomWithoutDuplicates() {
        val cache = GroupRoomCache(maxRooms = 4, maxEventsPerRoom = 10)
        cache.addEvents(ref, "chat", listOf(event("1", 1), event("2", 2)))
        cache.addEvents(ref, "chat", listOf(event("2", 2), event("3", 3)))
        assertEquals(listOf("1", "2", "3"), cache.events(ref, "chat").map { it.id }.sorted())
        // 画面の種類(チャット・スレッド)ごとに分ける。
        assertTrue(cache.events(ref, "thread:x").isEmpty())
    }

    @Test
    fun keepsNewestMessagesButAlwaysKeepsGroupStateEvents() {
        val cache = GroupRoomCache(maxRooms = 4, maxEventsPerRoom = 3)
        val members = event("m", 0, kind = GROUP_MEMBERS_KIND)
        cache.addEvents(ref, "chat", listOf(members) + (1..5).map { event("e$it", it.toLong()) })
        val ids = cache.events(ref, "chat").map { it.id }.toSet()
        // メンバー一覧などの状態は件数制限で落とさない。発言は新しい方を残す。
        assertEquals(setOf("m", "e3", "e4", "e5"), ids)
    }

    @Test
    fun dropsLeastRecentlyUsedRoom() {
        val cache = GroupRoomCache(maxRooms = 2, maxEventsPerRoom = 10)
        val a = GroupRef("wss://g.example", "a")
        val b = GroupRef("wss://g.example", "b")
        val c = GroupRef("wss://g.example", "c")
        cache.addEvents(a, "chat", listOf(event("1", 1)))
        cache.addEvents(b, "chat", listOf(event("2", 2)))
        cache.events(a, "chat")
        cache.addEvents(c, "chat", listOf(event("3", 3)))
        assertTrue(cache.events(b, "chat").isEmpty())
        assertEquals(1, cache.events(a, "chat").size)
    }

    @Test
    fun joinedHintAndMetadata() {
        val cache = GroupRoomCache(maxRooms = 4, maxEventsPerRoom = 10)
        assertNull(cache.joinedHint(ref))
        cache.setJoinedHint(ref, true)
        assertEquals(true, cache.joinedHint(ref))
        cache.setJoinedHint(ref, false)
        assertEquals(false, cache.joinedHint(ref))
    }
}
