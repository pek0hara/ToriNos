package com.nostr.torinos.ui.article

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.*
import kotlin.test.*

class ArticlePaginationTest {
    private fun page(ids: Set<String>, uncertain: Boolean = true, outcome: RelayOutcome = RelayOutcome.Eose) =
        FiniteFetchResult(setOf("relay"), ids.associateWith { NostrEvent(it, "author", 10, 30023, emptyList(), "", "sig") },
            ids.associateWith { setOf("relay") },
            mapOf("relay" to RelayPageObservation(outcome, ids, 10, uncertain)), FetchStopReason.AllRelaysFinished)

    @Test fun sameSecondDuplicatePageDoesNotSilentlyFinishOrAdvance() {
        val result = articlePaginationDecision(page(setOf("a", "b")), setOf("a", "b"), true)
        assertFalse(result.canLoadMore)
        assertTrue(result.incomplete)
    }
    @Test fun inclusiveBoundaryCanRevealAnotherEventAtTheSameSecond() {
        val result = articlePaginationDecision(page(setOf("a", "b")), setOf("a"), true)
        assertTrue(result.canLoadMore)
        assertFalse(result.incomplete)
    }
    @Test fun confirmedEndIsDifferentFromIncompleteResponse() {
        assertEquals(ArticlePaginationDecision(false, false), articlePaginationDecision(page(emptySet(), false), setOf("a"), true))
        assertEquals(ArticlePaginationDecision(false, true), articlePaginationDecision(page(setOf("b"), outcome = RelayOutcome.TimedOut), setOf("a"), true))
    }
}
