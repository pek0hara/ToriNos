package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupConversationsTest {
    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val bob = "d".repeat(64)
    private val relayKey = "c".repeat(64)

    private fun id(n: Int) = n.toString().padStart(64, '0')
    private fun ev(n: Int, kind: Int, tags: List<List<String>> = emptyList(), content: String = "m$n", pubkey: String = alice, createdAt: Long = n.toLong()) =
        NostrEvent(id(n), pubkey, createdAt, kind, listOf(listOf("h", "g")) + tags, content, "sig")

    private fun reply(root: Int) = listOf(listOf("e", id(root), "", "reply"))
    private fun nested(root: Int, parent: Int) = listOf(listOf("e", id(root), "", "root"), listOf("e", id(parent), "", "reply"))

    private fun buzz() = GroupTimeline("g", me, GroupFlavor.Buzz, relayPubkey = relayKey)
    private fun standard() = GroupTimeline("g", me, GroupFlavor.Standard)

    @Test
    fun buzzThreadBecomesOneConversationWithTheLatestReply() {
        val t = buzz()
        t.add(listOf(
            ev(1, 9, content = "root"),
            ev(2, 9, reply(1), "first", pubkey = bob),
            ev(5, 9, nested(1, 2), "latest", pubkey = bob),
            ev(3, 9, content = "plain"),
        ))

        val conversations = t.conversations()
        assertEquals(listOf(id(1), id(3)), conversations.map { it.rootId })
        val thread = conversations.first()
        assertEquals("root", thread.root?.body)
        assertEquals("latest", thread.latestReply?.body)
        assertEquals(2, thread.replyCount)
        assertEquals(5L, thread.lastActivity)
        val plain = conversations[1]
        assertNull(plain.latestReply)
        assertEquals(0, plain.replyCount)
        assertEquals(3L, plain.lastActivity)
    }

    @Test
    fun repliesWhoseRootIsNotLoadedStillFormAConversation() {
        val t = buzz()
        t.add(listOf(ev(4, 9, reply(1), "orphan")))

        val conversation = t.conversations().single()
        assertEquals(id(1), conversation.rootId)
        assertNull(conversation.root)
        assertEquals("orphan", conversation.latestReply?.body)
        assertEquals(4L, conversation.lastActivity)
    }

    @Test
    fun deletedRootHidesTheWholeConversation() {
        val t = buzz()
        t.add(listOf(
            ev(1, 9, content = "root"),
            ev(2, 9, reply(1), pubkey = bob),
            ev(3, 5, listOf(listOf("e", id(1)))),
        ))
        assertTrue(t.conversations().isEmpty())
    }

    @Test
    fun deletedLatestReplyFallsBackToThePreviousOne() {
        val t = buzz()
        t.add(listOf(
            ev(1, 9),
            ev(2, 9, reply(1), "kept", pubkey = bob),
            ev(3, 9, reply(1), "gone", pubkey = bob),
            ev(4, 5, listOf(listOf("e", id(3))), pubkey = bob),
        ))
        val conversation = t.conversations().single()
        assertEquals("kept", conversation.latestReply?.body)
        assertEquals(1, conversation.replyCount)
        assertEquals(2L, conversation.lastActivity)
    }

    @Test
    fun editIsReflectedInTheRootAndReply() {
        val t = buzz()
        t.add(listOf(
            ev(1, 9, content = "old root"),
            ev(2, 9, reply(1), "old reply", pubkey = bob),
            ev(10, BUZZ_EDIT_KIND, listOf(listOf("e", id(1))), "new root"),
            ev(11, BUZZ_EDIT_KIND, listOf(listOf("e", id(2))), "new reply", pubkey = bob),
        ))
        val conversation = t.conversations().single()
        assertEquals("new root", conversation.root?.body)
        assertEquals("new reply", conversation.latestReply?.body)
        // 編集は動きに数えない（並びが編集で変わらない）。
        assertEquals(2L, conversation.lastActivity)
    }

    @Test
    fun mutedAuthorsAreSkipped() {
        val t = buzz()
        t.add(listOf(
            ev(1, 9, content = "root"),
            ev(2, 9, reply(1), "kept", pubkey = me),
            ev(3, 9, reply(1), "muted reply", pubkey = bob),
            ev(4, 9, content = "muted root", pubkey = bob),
        ))
        val conversations = t.conversations(muted = setOf(bob))
        assertEquals(listOf(id(1)), conversations.map { it.rootId })
        assertEquals("kept", conversations.single().latestReply?.body)
        assertEquals(1, conversations.single().replyCount)
        assertEquals(2L, conversations.single().lastActivity)
    }

    @Test
    fun buzzForumPostsCollectTheirComments() {
        val t = buzz()
        t.add(listOf(
            ev(1, BUZZ_FORUM_POST_KIND, content = "post"),
            ev(6, BUZZ_FORUM_COMMENT_KIND, reply(1), "comment", pubkey = bob),
        ))
        val conversation = t.conversations().single()
        assertEquals("post", conversation.root?.body)
        assertEquals("comment", conversation.latestReply?.body)
        assertEquals(6L, conversation.lastActivity)
    }

    @Test
    fun standardChatMessagesAreEachTheirOwnConversation() {
        val t = standard()
        t.add(listOf(
            ev(1, GROUP_CHAT_KIND, content = "hello"),
            ev(2, GROUP_CHAT_KIND, listOf(listOf("q", id(1), "", alice)), "quoted reply", pubkey = bob),
        ))
        val conversations = t.conversations()
        assertEquals(listOf(id(2), id(1)), conversations.map { it.rootId })
        assertTrue(conversations.all { it.latestReply == null && it.replyCount == 0 })
        assertEquals(id(1), conversations.first().root?.replyTo?.event?.id)
    }

    @Test
    fun standardThreadsCollectTheirComments() {
        val t = standard()
        t.add(listOf(
            ev(1, GROUP_THREAD_KIND, listOf(listOf("title", "topic")), "body"),
            ev(7, GROUP_COMMENT_KIND, listOf(listOf("E", id(1), "", alice), listOf("K", "11")), "comment", pubkey = bob),
            ev(3, GROUP_CHAT_KIND, content = "chat"),
        ))
        val conversations = t.conversations()
        assertEquals(listOf(id(1), id(3)), conversations.map { it.rootId })
        assertEquals("comment", conversations.first().latestReply?.body)
        assertEquals(1, conversations.first().replyCount)
    }

    @Test
    fun sameActivityIsOrderedById() {
        val t = buzz()
        t.add(listOf(ev(2, 9, createdAt = 10), ev(1, 9, createdAt = 10)))
        assertEquals(listOf(id(1), id(2)), t.conversations().map { it.rootId })
    }
}
