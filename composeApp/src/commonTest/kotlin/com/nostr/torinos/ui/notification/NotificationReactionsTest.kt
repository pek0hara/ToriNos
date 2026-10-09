package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.UnicodeReaction
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationReactionsTest {
    @Test
    fun countsPlainLikesIncludingEmptyContent() {
        val summary = notificationReactions(listOf(event("a", "+"), event("b", "  ")))
        assertEquals(NotificationReactions(totalCount = 2, likeCount = 2), summary)
    }

    @Test
    fun aggregatesMixedReactionsWithoutTreatingEmojiAsPlainLikes() {
        val tags = listOf(listOf("emoji", "cat", "https://example.com/cat.png", "30030:author:cats"))
        val summary = notificationReactions(listOf(
            event("a", "+"),
            event("b", " 💜 "),
            event("c", "💜"),
            event("d", ":cat:", tags),
            event("e", ":cat:", tags),
            event("f", ":cat:", listOf(listOf("emoji", "cat", "https://example.com/other.png"))),
        ))
        assertEquals(6, summary.totalCount)
        assertEquals(1, summary.likeCount)
        assertEquals(listOf(UnicodeReaction("💜", 2)), summary.unicodeReactions)
        assertEquals(listOf(2, 1), summary.customReactions.map { it.count })
        assertEquals(listOf("https://example.com/cat.png", "https://example.com/other.png"), summary.customReactions.map { it.imageUrl })
    }

    @Test
    fun preservesNegativeAndUnknownReactions() {
        val summary = notificationReactions(listOf(event("a", "-"), event("b", ":missing:"), event("c", "hello")))
        assertEquals(0, summary.likeCount)
        assertEquals(listOf(UnicodeReaction("👎"), UnicodeReaction(":missing:"), UnicodeReaction("hello")), summary.unicodeReactions)
        assertEquals(3, summary.totalCount)
    }

    @Test
    fun ignoresDuplicateEventsAndOtherEventKinds() {
        val reaction = event("a", "+")
        assertEquals(NotificationReactions(totalCount = 1, likeCount = 1),
            notificationReactions(listOf(reaction, reaction, event("b", "+").copy(kind = 1))))
        assertEquals(NotificationReactions(), notificationReactions(emptyList()))
    }

    @Test
    fun preservesCustomEmojiSetForLongPress() {
        val reaction = event("a", ":cat:", listOf(listOf("emoji", "cat", "https://example.com/cat.png", "30030:${"a".repeat(64)}:cats")))
        assertEquals("30030:${"a".repeat(64)}:cats", notificationReactions(listOf(reaction)).customReactions.single().setAddress?.value)
    }

    private fun event(id: String, content: String, tags: List<List<String>> = emptyList()) = NostrEvent(
        id = id, pubkey = "actor", createdAt = 1, kind = 7, tags = tags, content = content, sig = "",
    )
}
