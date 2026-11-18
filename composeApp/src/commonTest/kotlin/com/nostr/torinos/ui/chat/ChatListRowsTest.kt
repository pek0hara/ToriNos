package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.GROUP_CHAT_KIND
import com.nostr.torinos.chat.GROUP_METADATA_KIND
import com.nostr.torinos.chat.GroupRef
import com.nostr.torinos.chat.parseGroupMetadata
import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListRowsTest {
    private fun message(createdAt: Long) =
        NostrEvent("e$createdAt", "a".repeat(64), createdAt, GROUP_CHAT_KIND, listOf(listOf("h", "g")), "hi", "s")

    private fun row(relay: String, id: String, lastAt: Long? = null, listedName: String? = null, archived: Boolean = false) = ChatGroupRow(
        ref = GroupRef(relay, id),
        metadata = if (archived) {
            parseGroupMetadata(NostrEvent("m$id", "c".repeat(64), 1, GROUP_METADATA_KIND, listOf(listOf("d", id), listOf("archived", "true")), "", "s"), null)
        } else {
            null
        },
        joined = true,
        pending = false,
        lastMessage = lastAt?.let(::message),
        listedName = listedName,
    )

    @Test
    fun sectionsFollowTheNewestActivityAndKeepRowOrder() {
        // 行は新しい順に並んで渡される。
        val rows = listOf(
            row("wss://buzz.example", "talk", 300),
            row("wss://groups.example", "jp", 200),
            row("wss://buzz.example", "welcome", 100),
            row("wss://old.example", "x"),
        )
        val sections = groupRowsByRelay(rows)
        assertEquals(listOf("wss://buzz.example", "wss://groups.example", "wss://old.example"), sections.map { it.relayUrl })
        assertEquals(listOf("talk", "welcome"), sections.first().rows.map { it.ref.groupId })
    }

    @Test
    fun titleFallsBackToTheListedNameThenAShortId() {
        val longId = "781d1867c8b2f".padEnd(64, '0')
        assertEquals("781d1867…", row("wss://r.example", longId).title)
        assertEquals("雑談", row("wss://r.example", longId, listedName = "雑談").title)
        // 短い ID はそのまま。
        assertEquals("nostr-jp", row("wss://r.example", "nostr-jp").title)
    }

    @Test
    fun rowWithoutInfoOrMessagesIsUnresolved() {
        assertTrue(row("wss://r.example", "x").isUnresolved)
        assertFalse(row("wss://r.example", "x", lastAt = 10).isUnresolved)
    }

    @Test
    fun archivedRowsAreSplitOffKeepingOrder() {
        val rows = listOf(
            row("wss://buzz.example", "old-project", 400, archived = true),
            row("wss://buzz.example", "talk", 300),
            row("wss://groups.example", "jp", 200),
            row("wss://buzz.example", "2025", 100, archived = true),
        )
        val (active, archived) = splitArchivedRows(rows)
        assertEquals(listOf("talk", "jp"), active.map { it.ref.groupId })
        assertEquals(listOf("old-project", "2025"), archived.map { it.ref.groupId })
        assertTrue(archived.all { it.isArchived })
    }

    @Test
    fun buzzThreadReplyShowsItsRootAboveTheReply() {
        val root = NostrEvent("r1", "a".repeat(64), 10, GROUP_CHAT_KIND, listOf(listOf("h", "g")), "**root** post", "s")
        val reply = NostrEvent("e2", "b".repeat(64), 20, GROUP_CHAT_KIND, listOf(listOf("h", "g"), listOf("e", "r1", "", "reply")), "the reply", "s")
        val row = ChatGroupRow(
            ref = GroupRef("wss://buzz.example", "g"),
            metadata = null,
            joined = true,
            pending = false,
            lastMessage = reply,
            flavor = com.nostr.torinos.chat.GroupFlavor.Buzz,
            threadRoot = root,
            threadRootEditedBody = "edited root",
        )
        assertEquals("the reply", row.preview)
        assertEquals("edited root", row.threadRootPreview)
        // ルートが無ければ（トップレベルの発言、または読めていない）出さない。
        assertEquals(null, row.copy(threadRoot = null).threadRootPreview)
    }
}
