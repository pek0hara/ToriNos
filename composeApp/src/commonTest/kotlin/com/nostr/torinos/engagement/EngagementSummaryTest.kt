package com.nostr.torinos.engagement

import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngagementSummaryTest {
    private val image = "https://example.com/bird.webp"

    @Test
    fun countsReactionsByKindAndOwnReactions() {
        val engagement = engagementOf(
            reactions = listOf(
                reaction("like", "+", pubkey = "me"),
                reaction("empty", ""),
                reaction("bird", ":bird:", tags = listOf(listOf("emoji", "bird", image))),
                reaction("bird2", ":bird:", tags = listOf(listOf("emoji", "bird", image)), pubkey = "me"),
                reaction("call", "🤙"),
                reaction("call2", "🤙"),
            ),
        )

        val summary = summarizeEngagement(engagement, ownPubkey = "me")

        assertEquals(6, summary.reactionCount)
        assertEquals(2, summary.likeCount)
        assertEquals(listOf(CustomReaction("bird", image, count = 2)), summary.customReactions)
        assertEquals(listOf(UnicodeReaction("🤙", count = 2)), summary.unicodeReactions)
        assertEquals("like", summary.ownLikeEventId)
        assertEquals(mapOf(ReactionOption.Custom("bird", image).key to "bird2"), summary.ownEmojiReactionEventIds)
    }

    @Test
    fun repostsIncludeQuotesAndOwnRepost() {
        val engagement = engagementOf(
            reposts = listOf(event("rp1", 6, pubkey = "me"), event("rp2", 16)),
            quotes = listOf(event("q1", 1)),
            replies = listOf(event("reply", 1), event("reply2", 1111)),
        )

        val summary = summarizeEngagement(engagement, ownPubkey = "me")

        assertEquals(3, summary.repostCount)
        assertEquals("rp1", summary.ownRepostEventId)
        assertEquals(2, summary.replyCount)
    }

    @Test
    fun relayScopeCountsOnlyEventsFromThoseRelays() {
        val engagement = engagementOf(
            reactions = listOf(reaction("a", "+"), reaction("b", "+")),
            sources = mapOf("a" to setOf("wss://one"), "b" to setOf("wss://two")),
        )

        val summary = summarizeEngagement(engagement, ownPubkey = null, relayScope = setOf("wss://one"))

        assertEquals(1, summary.reactionCount)
        assertNull(summary.ownLikeEventId)
    }

    private fun engagementOf(
        reactions: List<NostrEvent> = emptyList(),
        reposts: List<NostrEvent> = emptyList(),
        replies: List<NostrEvent> = emptyList(),
        quotes: List<NostrEvent> = emptyList(),
        sources: Map<String, Set<String>> = emptyMap(),
    ) = TargetEngagement(
        reactions = reactions.associateBy { it.id },
        reposts = reposts.associateBy { it.id },
        replies = replies.associateBy { it.id },
        quotes = quotes.associateBy { it.id },
        sourceRelays = sources,
    )

    private fun reaction(id: String, content: String, tags: List<List<String>> = emptyList(), pubkey: String = "p-$id") =
        event(id, 7, listOf(listOf("e", "target")) + tags, content, pubkey)

    private fun event(id: String, kind: Int, tags: List<List<String>> = emptyList(), content: String = "", pubkey: String = "p-$id") =
        NostrEvent(id = id, pubkey = pubkey, createdAt = 1, kind = kind, tags = tags, content = content, sig = "sig")
}
