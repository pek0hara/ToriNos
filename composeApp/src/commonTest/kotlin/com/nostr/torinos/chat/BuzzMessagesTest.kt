package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuzzMessagesTest {
    private val root = "1".repeat(64)
    private val parent = "2".repeat(64)
    private val alice = "b".repeat(64)

    private fun ev(id: String, tags: List<List<String>> = listOf(listOf("h", "g")), kind: Int = 9) =
        NostrEvent(id, alice, 1, kind, tags, "x", "sig")

    @Test
    fun topLevelBuzzMessageHasOnlyGroupTag() {
        val draft = buildBuzzMessage("g", "こんにちは", threadRoot = null, parent = null)
        assertEquals(9, draft.kind)
        assertEquals(listOf(listOf("h", "g")), draft.tags)
        assertEquals("こんにちは", draft.content)
    }

    @Test
    fun directReplyUsesReplyMarkerOnRoot() {
        val draft = buildBuzzMessage("g", "返信", threadRoot = ev(root), parent = ev(root))
        assertEquals(listOf(listOf("h", "g"), listOf("e", root, "", "reply"), listOf("p", alice)), draft.tags)
    }

    @Test
    fun nestedReplyHasRootAndReply() {
        val draft = buildBuzzMessage("g", "返信", threadRoot = ev(root), parent = ev(parent))
        assertTrue(listOf("e", root, "", "root") in draft.tags)
        assertTrue(listOf("e", parent, "", "reply") in draft.tags)
    }

    @Test
    fun threadRootIsReadFromMarkers() {
        assertEquals(root, buzzThreadRootId(ev(parent, listOf(listOf("h", "g"), listOf("e", root, "", "reply")))))
        assertEquals(root, buzzThreadRootId(ev("3".repeat(64), listOf(listOf("h", "g"), listOf("e", root, "", "root"), listOf("e", parent, "", "reply")))))
        assertEquals(null, buzzThreadRootId(ev(root)))
    }

    @Test
    fun forumPostAndCommentBuilders() {
        val post = buildForumPost("g", "タイトル\n本文")
        assertEquals(45001, post.kind)
        assertEquals(listOf(listOf("h", "g")), post.tags)
        val comment = buildForumComment("g", "賛成", ev(root, kind = 45001))
        assertEquals(45003, comment.kind)
        assertTrue(listOf("e", root, "", "reply") in comment.tags)
    }

    @Test
    fun editTargetsTheMessage() {
        val edit = buildBuzzEdit("g", ev(root), "直した")
        assertEquals(40003, edit.kind)
        assertEquals(listOf(listOf("h", "g"), listOf("e", root)), edit.tags)
        assertEquals("直した", edit.content)
    }

    @Test
    fun buzzPublishableKindsIncludeForumAndEdit() {
        val forumEvent = NostrEvent("4".repeat(64), alice, 1, 45001, listOf(listOf("h", "g")), "", "sig")
        assertEquals(null, groupPublishViolation(forumEvent, "g", setOf("wss://r"), "wss://r"))
    }
}

class GroupThreadMessagesTest {
    private val relay = "wss://g.example"
    private val alice = "b".repeat(64)
    private val bob = "d".repeat(64)
    private val rootId = "1".repeat(64)
    private val parentId = "2".repeat(64)

    @Test
    fun threadCarriesTitle() {
        val draft = buildGroupThread("g", "タイトル", "本文")
        assertEquals(11, draft.kind)
        assertEquals(listOf(listOf("h", "g"), listOf("title", "タイトル")), draft.tags)
        assertEquals("本文", draft.content)
    }

    @Test
    fun commentOnThreadFollowsNip22() {
        val root = NostrEvent(rootId, alice, 1, 11, listOf(listOf("h", "g")), "", "s")
        val draft = buildGroupComment("g", "コメント", root, parent = null, relayUrl = relay)
        assertEquals(1111, draft.kind)
        assertEquals(
            listOf(
                listOf("h", "g"),
                listOf("E", rootId, relay, alice), listOf("K", "11"), listOf("P", alice),
                listOf("e", rootId, relay, alice), listOf("k", "11"), listOf("p", alice),
            ),
            draft.tags,
        )
    }

    @Test
    fun replyToCommentPointsParentAtTheComment() {
        val root = NostrEvent(rootId, alice, 1, 11, listOf(listOf("h", "g")), "", "s")
        val parent = NostrEvent(parentId, bob, 2, 1111, listOf(listOf("h", "g")), "", "s")
        val draft = buildGroupComment("g", "返信", root, parent, relay)
        assertTrue(listOf("e", parentId, relay, bob) in draft.tags)
        assertTrue(listOf("k", "1111") in draft.tags)
        assertTrue(listOf("E", rootId, relay, alice) in draft.tags)
    }

    @Test
    fun threadTitleIsRead() {
        val root = NostrEvent(rootId, alice, 1, 11, listOf(listOf("h", "g"), listOf("title", "題")), "本文", "s")
        assertEquals("題", groupPostTitle(root))
        val buzzPost = NostrEvent(rootId, alice, 1, 45001, listOf(listOf("h", "g")), "1行目\n2行目", "s")
        assertEquals("1行目", groupPostTitle(buzzPost))
    }
}
