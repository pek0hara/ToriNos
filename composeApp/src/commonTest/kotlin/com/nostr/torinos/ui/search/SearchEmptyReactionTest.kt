package com.nostr.torinos.ui.search

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.isLikeReaction
import kotlin.test.*

class SearchEmptyReactionTest {
    private fun reaction(content: String, kind: Int = 7) = NostrEvent("id", "author", 1, kind,
        listOf(listOf("e", "note")), content, "sig")
    @Test fun emptyReactionCountsAsLikeAndDuplicateIsIgnored() {
        val accumulator = SearchEngagementAccumulator(10)
        accumulator.track("note")
        accumulator.apply(reaction(""))
        accumulator.apply(reaction(""))
        assertEquals(1, accumulator.snapshot().getValue("note").likeReactionCount)
        assertEquals(1, accumulator.snapshot().getValue("note").reactionCount)
    }
    @Test fun commonLikePredicateExcludesDislikesEmojiAndOtherKinds() {
        assertTrue(reaction("").isLikeReaction())
        assertTrue(reaction("+").isLikeReaction())
        assertFalse(reaction("-").isLikeReaction())
        assertFalse(reaction("🔥").isLikeReaction())
        assertFalse(reaction("", kind = 1).isLikeReaction())
    }
}
