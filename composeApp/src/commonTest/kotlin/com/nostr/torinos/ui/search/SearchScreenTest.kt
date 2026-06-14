package com.nostr.torinos.ui.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
