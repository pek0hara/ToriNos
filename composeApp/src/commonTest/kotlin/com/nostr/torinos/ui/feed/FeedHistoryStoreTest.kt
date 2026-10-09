package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedHistoryStoreTest {
    @Test
    fun repeatedCommitPreservesEveryRelayAndFilter() {
        val store = FeedHistoryStore()
        val note = event("note", 1)
        store.commit(0, listOf(StoredFeedEvent(note, setOf("a"), setOf(0))))
        store.commit(0, listOf(StoredFeedEvent(note, setOf("b"), setOf(1))))
        store.commit(0, listOf(StoredFeedEvent(note, setOf("a"), setOf(0))))
        assertEquals(1, store.snapshot.size)
        assertEquals(setOf("a", "b"), store.snapshot.single().relayUrls)
        assertEquals(setOf(0, 1), store.snapshot.single().filterKeys)
    }

    @Test
    fun oldScopeCannotRestoreDiscardedHistory() {
        val store = FeedHistoryStore()
        store.replaceScope(1)
        assertEquals(FeedStoreCommitResult.RejectedStaleScope,
            store.commit(0, listOf(StoredFeedEvent(event("old", 1), setOf("a"), setOf(0)))))
        assertTrue(store.snapshot.isEmpty())
    }

    @Test
    fun startedPageIsStoredInFullAndLiveTrimProtectsVisibleSources() {
        val store = FeedHistoryStore(softLimit = 2)
        store.commit(0, (1..4).map { StoredFeedEvent(event("$it", it.toLong()), setOf("a"), setOf(0)) })
        assertEquals(4, store.snapshot.size)
        assertTrue(store.limitReached)
        store.trimLive(setOf("1"))
        assertEquals(setOf("1", "4"), store.snapshot.map { it.event.id }.toSet())
        assertTrue(store.limitReached)
    }

    private fun event(id: String, at: Long) = NostrEvent(id, "author", at, 1, emptyList(), "", "")
}
