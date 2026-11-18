package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.encodeNevent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupTimelineTest {
    private val me = "a".repeat(64)
    private val other = "b".repeat(64)
    private val admin = "d".repeat(64)

    private fun id(n: Int) = n.toString().padStart(64, '0')

    private fun chat(n: Int, createdAt: Long, pubkey: String = other, group: String = "g1", content: String = "m$n") =
        NostrEvent(id(n), pubkey, createdAt, GROUP_CHAT_KIND, listOf(listOf("h", group)), content, "sig")

    private fun deletion(n: Int, target: Int, pubkey: String, kind: Int = 5) =
        NostrEvent(id(n), pubkey, 500, kind, listOf(listOf("h", "g1"), listOf("e", id(target))), "", "sig")

    private fun reaction(n: Int, target: Int, pubkey: String, content: String = "+") =
        NostrEvent(id(n), pubkey, 600, 7, listOf(listOf("h", "g1"), listOf("e", id(target))), content, "sig")

    private fun timeline() = GroupTimeline(groupId = "g1", ownPubkey = me)

    @Test
    fun removeDropsGivenEvents() {
        val t = timeline()
        t.add(listOf(chat(1, 10), chat(2, 20), reaction(3, target = 1, pubkey = other)))
        t.remove(setOf(id(1), id(3)))
        assertEquals(listOf(id(2)), t.snapshot().messages.map { it.event.id })
    }

    @Test
    fun messagesAreOrderedOldestFirstAndDeduplicated() {
        val t = timeline()
        t.add(listOf(chat(2, 20), chat(1, 10), chat(3, 20), chat(2, 20)))

        assertEquals(listOf(id(1), id(2), id(3)), t.snapshot().messages.map { it.event.id })
    }

    @Test
    fun otherGroupsAndOtherKindsAreIgnored() {
        val t = timeline()
        t.add(listOf(chat(1, 10, group = "g2"), NostrEvent(id(2), other, 10, 1, listOf(listOf("h", "g1")), "", "sig")))

        assertTrue(t.snapshot().messages.isEmpty())
    }

    @Test
    fun authorDeletionRemovesOnlyTheirOwnMessage() {
        val t = timeline()
        t.add(listOf(chat(1, 10, pubkey = other), chat(2, 11, pubkey = me)))
        t.add(listOf(deletion(10, target = 1, pubkey = me), deletion(11, target = 2, pubkey = me)))

        assertEquals(listOf(id(1)), t.snapshot().messages.map { it.event.id })
    }

    @Test
    fun moderatorDeletionRemovesAnyMessageEvenIfItArrivesFirst() {
        val t = timeline()
        t.setModerators(setOf(admin))
        t.add(listOf(deletion(10, target = 1, pubkey = admin, kind = GROUP_DELETE_EVENT_KIND)))
        t.add(listOf(chat(1, 10), chat(2, 11)))

        assertEquals(listOf(id(2)), t.snapshot().messages.map { it.event.id })
    }

    @Test
    fun deletionByNonModeratorWithModerationKindIsIgnored() {
        val t = timeline()
        t.add(listOf(chat(1, 10), deletion(10, target = 1, pubkey = other, kind = GROUP_DELETE_EVENT_KIND)))

        assertEquals(listOf(id(1)), t.snapshot().messages.map { it.event.id })
    }

    @Test
    fun reactionsAreCountedPerContentAndOwnReactionIsMarked() {
        val t = timeline()
        t.add(listOf(chat(1, 10)))
        t.add(listOf(reaction(20, 1, other), reaction(21, 1, me), reaction(22, 1, other, "🎉"), reaction(21, 1, me)))

        val message = t.snapshot().messages.single()
        assertEquals(mapOf("+" to 2, "🎉" to 1), message.reactions.associate { it.content to it.count })
        assertTrue(message.reactions.single { it.content == "+" }.reactedByMe)
        assertFalse(message.reactions.single { it.content == "🎉" }.reactedByMe)
        assertEquals(id(21), message.reactions.single { it.content == "+" }.ownReactionId)
    }

    @Test
    fun reactorsAreListedOncePerPersonInTheOrderTheyReacted() {
        val t = timeline()
        t.add(listOf(chat(1, 10)))
        val third = "c".repeat(64)
        fun at(event: NostrEvent, createdAt: Long) = event.copy(createdAt = createdAt)
        t.add(
            listOf(
                at(reaction(20, 1, other), 700),
                at(reaction(21, 1, me), 650),
                at(reaction(22, 1, other), 640),
                at(reaction(23, 1, third), 800),
                at(reaction(24, 1, third, "🎉"), 610),
            ),
        )

        val reactions = t.snapshot().messages.single().reactions
        // 同じ人の重複は1回、最初に付けた時刻の順。
        assertEquals(listOf(other, me, third), reactions.single { it.content == "+" }.reactors)
        assertEquals(listOf(third), reactions.single { it.content == "🎉" }.reactors)
    }

    @Test
    fun customReactionsWithTheSameShortcodeButDifferentImagesAreCountedSeparately() {
        val t = timeline()
        t.add(listOf(chat(1, 10)))
        fun custom(n: Int, pubkey: String, content: String, url: String) =
            reaction(n, 1, pubkey, content).let { it.copy(tags = it.tags + listOf(listOf("emoji", content.trim(':'), url))) }
        t.add(
            listOf(
                custom(20, other, ":wave:", "https://x/a.png"),
                custom(21, me, ":Wave:", "https://x/a.png"),
                custom(22, other, ":wave:", "https://x/b.png"),
            ),
        )

        val reactions = t.snapshot().messages.single().reactions
        assertEquals(2, reactions.size)
        val a = reactions.single { it.emojiUrl == "https://x/a.png" }
        assertEquals(2, a.count)
        assertEquals(id(21), a.ownReactionId)
        val b = reactions.single { it.emojiUrl == "https://x/b.png" }
        assertEquals(1, b.count)
        assertFalse(b.reactedByMe)
    }

    @Test
    fun deletedReactionIsNotCounted() {
        val t = timeline()
        t.add(listOf(chat(1, 10), reaction(21, 1, me), deletion(30, target = 21, pubkey = me)))

        assertTrue(t.snapshot().messages.single().reactions.isEmpty())
    }

    @Test
    fun replyTargetIsResolvedFromLoadedMessages() {
        val t = timeline()
        val reply = NostrEvent(
            id(2), other, 20, GROUP_CHAT_KIND,
            listOf(listOf("h", "g1"), listOf("q", id(1), "wss://r", other)), "nostr:${encodeNevent(id(1), listOf("wss://r"), other)}\n返信", "sig",
        )
        t.add(listOf(chat(1, 10, content = "元"), reply))

        val message = t.snapshot().messages.last()
        assertEquals(id(1), message.replyToId)
        assertEquals("元", message.replyTo?.event?.content)
        assertEquals("返信", message.body)
    }

    @Test
    fun oldestCreatedAtIsReportedForPaging() {
        val t = timeline()
        t.add(listOf(chat(1, 30), chat(2, 10)))
        assertEquals(10L, t.snapshot().oldestCreatedAt)
    }

    @Test
    fun customEmojiComeFromTheMessageTags() {
        val t = timeline()
        val event = chat(1, 10, content = ":wave:").let { it.copy(tags = it.tags + listOf(listOf("emoji", "wave", "https://x/w.png"))) }
        t.add(listOf(event))
        assertEquals(mapOf("wave" to "https://x/w.png"), t.snapshot().messages.single().emojis)
    }
}
