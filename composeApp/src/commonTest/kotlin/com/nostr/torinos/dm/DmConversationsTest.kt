package com.nostr.torinos.dm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmConversationsTest {
    private val me = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val dave = "d".repeat(64)

    private fun msg(id: String, from: String, to: List<String>, at: Long, text: String = id) =
        DmMessage(id = id, sender = from, recipients = to, createdAt = at, content = text)

    private fun build(
        messages: List<DmMessage>,
        follows: Set<String> = setOf(bob),
        muted: Set<String> = emptySet(),
        lastRead: Map<String, Long> = emptyMap(),
        baseline: Long = 0,
    ) = buildDmConversations(messages, me, follows, muted, lastRead, baseline)

    @Test
    fun groupsByPeerAndSortsNewestFirst() {
        val lists = build(
            listOf(
                msg("1", bob, listOf(me), 100),
                msg("2", me, listOf(bob), 300),
                msg("3", carol, listOf(me), 200),
                msg("4", me, listOf(carol), 150),
            ),
        )
        assertEquals(listOf(listOf(bob), listOf(carol)), lists.conversations.map { it.peers })
        assertEquals("2", lists.conversations[0].last.id)
        assertEquals(dmConversationKey(setOf(bob)), lists.conversations[0].key)
    }

    @Test
    fun strangerWithoutMyReplyIsARequestUntilIReply() {
        val incoming = msg("1", carol, listOf(me), 100)
        assertEquals(listOf(listOf(carol)), build(listOf(incoming)).requests.map { it.peers })
        assertTrue(build(listOf(incoming)).conversations.isEmpty())

        val replied = build(listOf(incoming, msg("2", me, listOf(carol), 120)))
        assertTrue(replied.requests.isEmpty())
        assertEquals(listOf(listOf(carol)), replied.conversations.map { it.peers })
    }

    @Test
    fun followedPeerIsNeverARequest() {
        assertEquals(listOf(listOf(bob)), build(listOf(msg("1", bob, listOf(me), 100))).conversations.map { it.peers })
    }

    @Test
    fun mutedPeersAreHidden() {
        val lists = build(listOf(msg("1", bob, listOf(me), 100), msg("2", carol, listOf(me), 100)), muted = setOf(bob, carol))
        assertTrue(lists.conversations.isEmpty())
        assertTrue(lists.requests.isEmpty())
    }

    @Test
    fun groupConversationIsMarkedAndKeyedBySortedPeers() {
        val lists = build(listOf(msg("1", bob, listOf(me, dave), 100)), follows = setOf(bob))
        val conversation = lists.conversations.single()
        assertTrue(conversation.isGroup)
        assertEquals(listOf(bob, dave), conversation.peers)
    }

    @Test
    fun noteToSelfIsItsOwnConversation() {
        val conversation = build(listOf(msg("1", me, listOf(me), 100))).conversations.single()
        assertEquals(listOf(me), conversation.peers)
        assertFalse(conversation.isGroup)
        assertFalse(conversation.unread)
    }

    @Test
    fun duplicateRumorsCountOnce() {
        val m = msg("1", bob, listOf(me), 100)
        assertEquals(1, build(listOf(m, m.copy())).conversations.single().messageCount)
    }

    @Test
    fun unreadUsesLastReadThenBaseline() {
        val key = dmConversationKey(setOf(bob))
        val messages = listOf(msg("1", bob, listOf(me), 100))
        // 初回に読んだ時点（baseline）より前の分は未読にしない。
        assertFalse(build(messages, baseline = 150).conversations.single().unread)
        assertTrue(build(messages, baseline = 50).conversations.single().unread)
        assertFalse(build(messages, baseline = 50, lastRead = mapOf(key to 100)).conversations.single().unread)
        // 自分の発言が最後なら未読にしない。
        assertFalse(build(messages + msg("2", me, listOf(bob), 200), baseline = 50).conversations.single().unread)
    }

    @Test
    fun counts() {
        val lists = build(
            listOf(msg("1", bob, listOf(me), 100), msg("2", carol, listOf(me), 100)),
            baseline = 0,
        )
        assertEquals(1, lists.unreadConversationCount)
        assertEquals(1, lists.requests.size)
    }
}
