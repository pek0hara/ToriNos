package com.nostr.torinos.ui.thread

import com.nostr.torinos.engagement.EngagementDelta
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.EngagementStore
import com.nostr.torinos.engagement.PendingEngagementOperation
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.ui.feed.FeedViewModel
import com.nostr.torinos.ui.feed.noteEngagement
import com.nostr.torinos.ui.feed.withProjectedEngagement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** フィードとポスト詳細が同じストアから同じ件数を出すこと（プロフィールとポスト詳細で件数が食い違った不具合）。 */
class CrossScreenEngagementTest {
    private val owner = Any()

    @Test
    fun feedAndThreadShowTheSameCountsFromTheSameStore() {
        val store = EngagementStore()
        store.retain(owner, setOf(ROOT))
        listOf(
            reaction("r1", "🤙", "a", createdAt = 101),
            reaction("r2", "🤙", "b", createdAt = 102),
            reaction("r3", ":ok:", "c", listOf(listOf("emoji", "ok", "https://example.com/ok.webp")), createdAt = 103),
            event("rp", 6, "d", listOf(listOf("e", ROOT))),
            event("q", 1, "e", listOf(listOf("q", ROOT))),
        ).forEach { store.observe(it, "wss://relay") }
        // 同じイベントが別のリレーから再び届いても変わらない。
        store.observe(reaction("r1", "🤙", "a", createdAt = 101), "wss://other")
        val engagement = store.snapshot(ROOT)!!

        val feed = FeedViewModel.UiState().withProjectedEngagement(ROOT, engagement, OWN, emptyMap()).noteEngagement(ROOT)
        val thread = ThreadViewModel.UiState().withProjectedEngagement(ROOT, ROOT, engagement, OWN, emptyMap())

        assertEquals(3, feed.reactionCount)
        assertEquals(feed.reactionCount, thread.reactionCounts[ROOT])
        assertEquals(feed.unicodeReactions, thread.unicodeReactions[ROOT].orEmpty())
        assertEquals(feed.customReactions, thread.customReactions[ROOT].orEmpty())
        assertEquals(2, feed.repostCount)
        assertEquals(feed.repostCount, thread.repostCount)
        assertEquals(listOf("c", "b", "a"), thread.reactionPubkeys)
        assertEquals(listOf("d"), thread.repostPubkeys)
        assertEquals(listOf("q"), thread.quoteReposts.map { it.id })
    }

    @Test
    fun pendingOwnLikeIsShownOnBothScreens() {
        val store = EngagementStore()
        store.retain(owner, setOf(ROOT))
        store.observe(reaction("r1", "+", "a"), "wss://relay")
        val engagement = store.snapshot(ROOT)!!
        val pending = mapOf(
            EngagementSlot.Reaction to PendingEngagementOperation(
                id = EngagementOperationId("op"),
                request = EngagementRequest.AddLike,
                appliedDelta = EngagementDelta(reactionCount = 1, likeReactionCount = 1),
            ),
        )

        val feed = FeedViewModel.UiState().withProjectedEngagement(ROOT, engagement, OWN, pending)
        val thread = ThreadViewModel.UiState().withProjectedEngagement(ROOT, ROOT, engagement, OWN, pending)

        assertEquals(2, feed.reactionCounts[ROOT])
        assertEquals(2, thread.reactionCounts[ROOT])
        assertTrue(feed.isLiked(ROOT))
        assertTrue(thread.isLiked(ROOT))
        assertEquals(listOf(OWN, "a"), thread.reactionPubkeys)
    }

    @Test
    fun pendingLikeIsNotAddedTwiceOnceTheStoreHasIt() {
        val store = EngagementStore()
        store.retain(owner, setOf(ROOT))
        store.observe(reaction("mine", "+", OWN), "wss://relay")
        val pending = mapOf(
            EngagementSlot.Reaction to PendingEngagementOperation(
                id = EngagementOperationId("op"),
                request = EngagementRequest.AddLike,
                appliedDelta = EngagementDelta(reactionCount = 1, likeReactionCount = 1),
            ),
        )

        val thread = ThreadViewModel.UiState().withProjectedEngagement(ROOT, ROOT, store.snapshot(ROOT)!!, OWN, pending)

        assertEquals(1, thread.reactionCounts[ROOT])
        assertEquals(listOf(OWN), thread.reactionPubkeys)
    }

    private fun reaction(
        id: String,
        content: String,
        pubkey: String,
        tags: List<List<String>> = emptyList(),
        createdAt: Long = 100,
    ) = event(id, 7, pubkey, listOf(listOf("e", ROOT)) + tags, content, createdAt)

    private fun event(
        id: String,
        kind: Int,
        pubkey: String,
        tags: List<List<String>>,
        content: String = "",
        createdAt: Long = 100,
    ) = NostrEvent(id = id, pubkey = pubkey, createdAt = createdAt, kind = kind, tags = tags, content = content, sig = "sig")

    private companion object {
        const val ROOT = "root"
        const val OWN = "me"
    }
}
