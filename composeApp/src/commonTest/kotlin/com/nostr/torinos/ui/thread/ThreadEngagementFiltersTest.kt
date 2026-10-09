package com.nostr.torinos.ui.thread

import com.nostr.torinos.model.NostrFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadEngagementFiltersTest {
    @Test
    fun rootGetsItsOwnFilterSoRepliesCannotUseUpItsLimit() {
        val filters = threadEngagementFilters("root", listOf("root", "reply-1", "reply-2")) { ids ->
            NostrFilter(kinds = listOf(7), eTags = ids, limit = 500)
        }

        assertEquals(listOf(listOf("root"), listOf("reply-1", "reply-2")), filters.map { it.eTags })
    }

    @Test
    fun onlyRootProducesSingleFilter() {
        val filters = threadEngagementFilters("root", listOf("root")) { ids -> NostrFilter(eTags = ids) }

        assertEquals(listOf(listOf("root")), filters.map { it.eTags })
    }

    @Test
    fun rootEvictedFromWatchListStillGetsItsFilter() {
        val filters = threadEngagementFilters("root", listOf("reply-1")) { ids -> NostrFilter(eTags = ids) }

        assertEquals(listOf(listOf("root"), listOf("reply-1")), filters.map { it.eTags })
    }
}
