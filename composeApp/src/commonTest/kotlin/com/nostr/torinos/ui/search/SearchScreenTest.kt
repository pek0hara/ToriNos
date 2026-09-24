package com.nostr.torinos.ui.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchScreenTest {
    @Test
    fun spotifySearchUriOrNull_encodesSearchText() {
        assertEquals(
            "spotify:search:King%20Gnu",
            spotifySearchUriOrNull("spotify:search:King Gnu"),
        )
    }

    @Test
    fun spotifySearchUriOrNull_preservesExistingPercentEncodedBytes() {
        assertEquals(
            "spotify:search:King%20Gnu",
            spotifySearchUriOrNull("spotify:search:King%20Gnu"),
        )
    }

    @Test
    fun spotifySearchUriOrNull_encodesNonAsciiSearchText() {
        assertEquals(
            "spotify:search:%E6%96%B0%E5%AE%9D%E5%B3%B6",
            spotifySearchUriOrNull("spotify:search:新宝島"),
        )
    }

    @Test
    fun spotifySearchUriOrNull_returnsNullForNormalSearchText() {
        assertNull(spotifySearchUriOrNull("King Gnu"))
    }

    @Test
    fun engagementFilters_areFiniteAndTimeBounded() {
        val eventIds = listOf("event-1", "event-2")
        val since = 1234L

        val filters = engagementFilters(eventIds, since)

        assertEquals(6, filters.size)
        assertTrue(filters.all { it.limit == SearchViewModel.ENGAGEMENT_FILTER_LIMIT })
        assertTrue(filters.all { it.since == since })
        assertTrue(filters.all { filter ->
            filter.eTags == eventIds || filter.rootEventTags == eventIds || filter.qTags == eventIds
        })
    }
}
