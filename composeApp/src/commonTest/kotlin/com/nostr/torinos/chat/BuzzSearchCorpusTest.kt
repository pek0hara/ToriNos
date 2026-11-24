package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuzzSearchCorpusTest {
    private val relayUrl = "wss://png.communities.buzz.xyz"
    private val relayKey = "f".repeat(64)
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val channel = "11111111-1111-1111-1111-111111111111"
    private var seq = 0

    private fun event(kind: Int, content: String, author: String = alice, createdAt: Long = 100, tags: List<List<String>> = listOf(listOf("h", channel))) =
        NostrEvent((++seq).toString().padStart(64, '0'), author, createdAt, kind, tags, content, "sig")

    private fun corpus() = BuzzSearchCorpus(relayUrl, relayKey)

    @Test
    fun searchSortsByRankThenNewest() {
        val c = corpus()
        val old = event(9, "申請しました", createdAt = 1)
        val new = event(9, "OpenSatsの申請進めよう", createdAt = 3)
        val whole = event(9, "申請 の話", createdAt = 2)
        val other = event(9, "関係ない", createdAt = 4)
        c.add(listOf(old, new, whole, other))
        assertEquals(listOf(whole.id, old.id, new.id), c.search("申請").map { it.id })
    }

    @Test
    fun deletionsByAuthorOrModeratorHideMessages() {
        val c = corpus()
        val mine = event(9, "申請 1")
        val other = event(9, "申請 2")
        val moderated = event(9, "申請 3")
        c.add(
            listOf(
                mine, other, moderated,
                event(5, "", author = alice, tags = listOf(listOf("e", mine.id))),
                // 他人の kind 5 は効かない。
                event(5, "", author = bob, tags = listOf(listOf("e", other.id))),
                event(9005, "", author = relayKey, tags = listOf(listOf("h", channel), listOf("e", moderated.id))),
            ),
        )
        assertEquals(listOf(other.id), c.search("申請").map { it.id })
    }

    @Test
    fun latestOwnEditReplacesBody() {
        val c = corpus()
        val msg = event(9, "古い本文")
        c.add(
            listOf(
                msg,
                event(40003, "新しい申請の本文", createdAt = 200, tags = listOf(listOf("h", channel), listOf("e", msg.id))),
                event(40003, "途中の本文", createdAt = 150, tags = listOf(listOf("h", channel), listOf("e", msg.id))),
                // 他人の編集は効かない。
                event(40003, "乗っ取り", author = bob, createdAt = 300, tags = listOf(listOf("h", channel), listOf("e", msg.id))),
            ),
        )
        assertTrue(c.search("古い").isEmpty())
        assertEquals("新しい申請の本文", c.search("申請").single().preview)
        assertTrue(c.search("乗っ取り").isEmpty())
        assertEquals(mapOf(msg.id to "新しい申請の本文"), c.editedBodies())
        assertTrue(msg.id !in c.deletedIds())
    }

    @Test
    fun oldestTimeAndDuplicatesAcrossPages() {
        val c = corpus()
        val a = event(9, "x", createdAt = 50)
        c.add(listOf(a, event(9, "y", createdAt = 80)))
        c.add(listOf(a, event(9, "z", createdAt = 20)))
        assertEquals(3, c.messageCount)
        assertEquals(20L, c.oldestMessageAt)
        assertEquals(80L, c.newestMessageAt)
    }
}

class BuzzLocalPeopleTest {
    @Test
    fun localPeopleMatchNormalizedNames() {
        val me = "e".repeat(64)
        val people = listOf(
            BuzzSearchPerson("1".repeat(64), com.nostr.torinos.model.NostrProfile(name = "peko", displayName = "ペコ")),
            BuzzSearchPerson("2".repeat(64), com.nostr.torinos.model.NostrProfile(name = "kenkyusha", displayName = "研究者")),
            BuzzSearchPerson(me, com.nostr.torinos.model.NostrProfile(name = "pekome")),
        )
        assertEquals(listOf("1".repeat(64)), buzzLocalPeople(people, "ぺこ", me).map { it.pubkey })
        assertEquals(listOf("1".repeat(64)), buzzLocalPeople(people, "PEK", me).map { it.pubkey })
        assertEquals(listOf("2".repeat(64)), buzzLocalPeople(people, "研究", me).map { it.pubkey })
    }
}
