package com.nostr.torinos.ui.feed

import com.nostr.torinos.engagement.EngagementStore
import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 付随情報をまとめて反映した結果が、1件ずつ反映した結果と一致すること（設計書 engagement-fetch-redesign §13）。 */
class FeedEngagementProjectionTest {
    private val owner = Any()

    @Test
    fun batchProjectionMatchesProjectingOneByOne() {
        val store = EngagementStore()
        store.retain(owner, setOf("a", "b", "c"))
        listOf(
            event("r1", 7, "p1", listOf(listOf("e", "a")), content = "+"),
            event("r2", 7, "p2", listOf(listOf("e", "a")), content = "🤙"),
            event("rp", 6, "p3", listOf(listOf("e", "b"))),
            event("reply", 1, "p4", listOf(listOf("e", "b", "", "root"))),
        ).forEach { store.observe(it, "wss://relay") }
        // "c" は付随情報なしで追跡、"gone" は追跡をやめた投稿。どちらも以前の表示が残っている。
        val stale = FeedViewModel.UiState()
            .withProjectedEngagement("c", store.snapshot("a")!!, OWN, emptyMap())
            .withProjectedEngagement("gone", store.snapshot("b")!!, OWN, emptyMap())
        val targets = listOf(
            EngagementTarget("a", store.snapshot("a"), emptyMap()),
            EngagementTarget("b", store.snapshot("b"), emptyMap()),
            EngagementTarget("c", store.snapshot("c"), emptyMap()),
            EngagementTarget("gone", null, emptyMap()),
        )

        val oneByOne = targets.fold(stale) { state, target -> state.withProjectedEngagements(listOf(target), OWN) }
        val batched = stale.withProjectedEngagements(targets, OWN)

        assertEquals(oneByOne, batched)
        assertEquals(2, batched.reactionCounts["a"])
        assertEquals(1, batched.repostCounts["b"])
        assertEquals(0, batched.reactionCounts["c"])
        assertFalse("gone" in batched.reactionCounts)
        assertFalse("gone" in batched.repostEvents)
    }

    @Test
    fun singleProjectionMatchesBatchOfOne() {
        val store = EngagementStore()
        store.retain(owner, setOf("a"))
        store.observe(event("r1", 7, "p1", listOf(listOf("e", "a")), content = "+"), "wss://relay")
        val engagement = store.snapshot("a")!!

        assertEquals(
            FeedViewModel.UiState().withProjectedEngagement("a", engagement, OWN, emptyMap()),
            FeedViewModel.UiState().withProjectedEngagements(listOf(EngagementTarget("a", engagement, emptyMap())), OWN),
        )
    }

    private fun event(id: String, kind: Int, pubkey: String, tags: List<List<String>>, content: String = "") =
        NostrEvent(id, pubkey, 100, kind, tags, content, "sig")

    private companion object {
        const val OWN = "own"
    }
}
