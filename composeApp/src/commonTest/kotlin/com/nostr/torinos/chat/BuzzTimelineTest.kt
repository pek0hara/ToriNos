package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuzzTimelineTest {
    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val relayKey = "c".repeat(64)
    private val actor = "e".repeat(64)

    private fun id(n: Int) = n.toString().padStart(64, '0')
    private fun ev(n: Int, kind: Int, tags: List<List<String>>, content: String = "", pubkey: String = alice, createdAt: Long = n.toLong()) =
        NostrEvent(id(n), pubkey, createdAt, kind, listOf(listOf("h", "g")) + tags, content, "sig")

    private fun timeline() = GroupTimeline("g", me, GroupFlavor.Buzz, relayPubkey = relayKey)

    @Test
    fun threadRepliesAreCountedOnTheRootNotShownInline() {
        val t = timeline()
        t.add(listOf(
            ev(1, 9, emptyList(), "root"),
            ev(2, 9, listOf(listOf("e", id(1), "", "reply")), "direct"),
            ev(3, 9, listOf(listOf("e", id(1), "", "root"), listOf("e", id(2), "", "reply")), "nested"),
        ))

        val messages = t.snapshot().messages
        assertEquals(listOf(id(1)), messages.map { it.event.id })
        assertEquals(2, messages.single().threadReplyCount)
        assertEquals(listOf(id(2), id(3)), t.thread(id(1)).replies.map { it.event.id })
        assertEquals("root", t.thread(id(1)).root?.body)
    }

    @Test
    fun latestEditByTheAuthorReplacesTheBody() {
        val t = timeline()
        t.add(listOf(
            ev(1, 9, emptyList(), "old"),
            ev(5, 40003, listOf(listOf("e", id(1))), "edited", createdAt = 50),
            ev(6, 40003, listOf(listOf("e", id(1))), "forged", pubkey = me, createdAt = 60),
            ev(7, 40003, listOf(listOf("e", id(1))), "older edit", createdAt = 40),
        ))

        val message = t.snapshot().messages.single()
        assertEquals("edited", message.body)
        assertTrue(message.edited)
    }

    @Test
    fun deletedEditIsIgnored() {
        val t = timeline()
        t.add(listOf(
            ev(1, 9, emptyList(), "old"),
            ev(5, 40003, listOf(listOf("e", id(1))), "edited", createdAt = 50),
            ev(8, 5, listOf(listOf("e", id(5))), createdAt = 70),
        ))
        assertEquals("old", t.snapshot().messages.single().body)
    }

    @Test
    fun agentMessageBodyComesFromJson() {
        val t = timeline()
        t.add(listOf(ev(1, 40002, emptyList(), """{"content":"エージェントです","other":1}""")))
        assertEquals("エージェントです", t.snapshot().messages.single().body)
    }

    @Test
    fun relaySignedMessageIsAttributedToActor() {
        val t = timeline()
        t.add(listOf(ev(1, 9, listOf(listOf("actor", actor)), "via bridge", pubkey = relayKey)))
        assertEquals(actor, t.snapshot().messages.single().author)
        t.add(listOf(ev(2, 9, listOf(listOf("p", alice)), "first p", pubkey = relayKey)))
        // h が先頭にあるので、先頭の p は使わず署名者のまま（Buzz は tags[0] が p のときだけ使う）。
        assertEquals(relayKey, t.snapshot().messages.last().author)
    }

    @Test
    fun copySentFromThreadIsMarked() {
        val t = timeline()
        t.add(listOf(ev(1, 9, listOf(listOf("buzz:sent-from-thread", id(9), "見出し"), listOf("buzz:copy-of", id(8))), "copy")))
        assertEquals("見出し", t.snapshot().messages.single().sentFromThreadLabel)
    }

    @Test
    fun tappingCopySentFromThreadOpensOriginalThread() {
        val t = timeline()
        t.add(listOf(
            ev(1, 9, emptyList(), "plain"),
            ev(2, 9, listOf(listOf("buzz:sent-from-thread", id(9), "見出し")), "copy"),
            ev(3, 9, listOf(listOf("buzz:sent-from-thread", "broken", "見出し")), "bad root"),
        ))
        val messages = t.snapshot().messages.associateBy { it.body }
        // 普通の発言はその発言をスレッドの元にする。
        assertEquals(id(1), messages.getValue("plain").openThreadRootId)
        // スレッドから送信したコピーは、元のスレッドを開く。
        assertEquals(id(9), messages.getValue("copy").openThreadRootId)
        // 元の ID が読めなければ、その発言を開く。
        assertEquals(id(3), messages.getValue("bad root").openThreadRootId)
    }

    @Test
    fun standardFlavorKeepsQuoteReplies() {
        val t = GroupTimeline("g", me, GroupFlavor.Standard, relayPubkey = null)
        t.add(listOf(ev(1, 9, emptyList(), "a"), ev(2, 9, listOf(listOf("q", id(1), "", alice)), "b")))
        assertEquals(2, t.snapshot().messages.size)
        assertNull(t.snapshot().messages.first().sentFromThreadLabel)
    }

    @Test
    fun forumPostsAndCommentsAreKeptApart() {
        val t = timeline()
        t.add(listOf(
            ev(1, 45001, emptyList(), "投稿タイトル\n本文"),
            ev(2, 45003, listOf(listOf("e", id(1), "", "reply")), "コメント"),
        ))
        val posts = t.forumPosts()
        assertEquals(listOf(id(1)), posts.map { it.event.id })
        assertEquals(1, posts.single().threadReplyCount)
        assertEquals(listOf(id(2)), t.thread(id(1)).replies.map { it.event.id })
    }

    @Test
    fun customEmojiComeFromTheMessageTagsWithLowercaseShortcodes() {
        val t = timeline()
        t.add(listOf(ev(1, 9, listOf(listOf("emoji", "YabaSugi", "https://x/y.png")), ":yabasugi:")))
        assertEquals(mapOf("yabasugi" to "https://x/y.png"), t.snapshot().messages.single().emojis)
    }

    @Test
    fun editWithEmojiTagsReplacesTheEmojiAndEditWithoutKeepsTheOriginal() {
        val t = timeline()
        t.add(listOf(
            ev(1, 9, listOf(listOf("emoji", "a", "https://x/a.png")), ":a:"),
            ev(5, 40003, listOf(listOf("e", id(1))), ":a: edited", createdAt = 50),
        ))
        assertEquals(mapOf("a" to "https://x/a.png"), t.snapshot().messages.single().emojis)

        t.add(listOf(ev(6, 40003, listOf(listOf("e", id(1)), listOf("emoji", "b", "https://x/b.png")), ":b:", createdAt = 60)))
        assertEquals(mapOf("b" to "https://x/b.png"), t.snapshot().messages.single().emojis)
    }

    @Test
    fun customEmojiReactionCarriesItsImage() {
        val t = timeline()
        t.add(listOf(
            ev(1, 9, emptyList(), "root"),
            ev(2, 7, listOf(listOf("e", id(1)), listOf("emoji", "pe", "https://x/pe.png")), ":pe:"),
            ev(3, 7, listOf(listOf("e", id(1))), "👍"),
        ))
        val reactions = t.snapshot().messages.single().reactions.associateBy { it.content }
        assertEquals("https://x/pe.png", reactions.getValue(":pe:").emojiUrl)
        assertNull(reactions.getValue("👍").emojiUrl)
    }

    @Test
    fun threadEventIdsListTheRootAndItsRepliesOnly() {
        val t = timeline()
        t.add(listOf(
            ev(1, 9, emptyList(), "root"),
            ev(2, 9, listOf(listOf("e", id(1), "", "reply")), "direct"),
            ev(3, 9, listOf(listOf("e", id(1), "", "root"), listOf("e", id(2), "", "reply")), "nested"),
            ev(4, 9, emptyList(), "other"),
            ev(5, 9, listOf(listOf("e", id(4), "", "reply")), "other reply"),
        ))

        assertEquals(setOf(id(1), id(2), id(3)), t.threadEventIds(id(1)).toSet())
    }

    @Test
    fun reactionToAThreadReplyIsShownInTheThread() {
        val t = timeline()
        t.add(listOf(
            ev(1, 9, emptyList(), "root"),
            ev(2, 9, listOf(listOf("e", id(1), "", "reply")), "reply"),
            ev(3, 7, listOf(listOf("e", id(2)), listOf("p", alice), listOf("emoji", "machi", "https://x/machi.png")), ":machi:", pubkey = me),
        ))

        val reaction = t.thread(id(1)).replies.single().reactions.single()
        assertEquals(":machi:", reaction.content)
        assertEquals("https://x/machi.png", reaction.emojiUrl)
        assertTrue(reaction.reactedByMe)
    }

    @Test
    fun reactionWithoutGroupTagIsShownButOneForAnotherGroupIsNot() {
        val t = timeline()
        fun raw(n: Int, tags: List<List<String>>, content: String) = NostrEvent(id(n), me, n.toLong(), 7, tags, content, "sig")
        t.add(listOf(
            ev(1, 9, emptyList(), "hello"),
            raw(2, listOf(listOf("e", id(1)), listOf("p", alice), listOf("emoji", "madi", "https://x/madi.png")), ":madi:"),
            raw(3, listOf(listOf("h", "other"), listOf("e", id(1))), "👍"),
        ))

        val reaction = t.snapshot().messages.single().reactions.single()
        assertEquals(":madi:", reaction.content)
        assertEquals("https://x/madi.png", reaction.emojiUrl)
    }

    @Test
    fun messageWithoutGroupTagIsStillDropped() {
        val t = timeline()
        t.add(listOf(NostrEvent(id(1), alice, 1, 9, emptyList(), "stray", "sig")))
        assertTrue(t.snapshot().messages.isEmpty())
    }
}
