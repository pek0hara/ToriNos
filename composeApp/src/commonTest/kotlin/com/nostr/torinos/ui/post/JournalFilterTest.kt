package com.nostr.torinos.ui.post

import kotlin.test.Test
import kotlin.test.assertEquals

class JournalFilterTest {
    @Test
    fun noSelectionShowsPostsRepostsAndReplies() {
        assertEquals(
            setOf(
                JournalEntryFilter.Post,
                JournalEntryFilter.Repost,
                JournalEntryFilter.Reply,
            ),
            effectiveJournalEntryFilters(emptySet()),
        )
    }

    @Test
    fun explicitSelectionShowsOnlySelectedTypes() {
        assertEquals(
            setOf(JournalEntryFilter.Repost, JournalEntryFilter.Reply),
            effectiveJournalEntryFilters(
                setOf(JournalEntryFilter.Repost, JournalEntryFilter.Reply),
            ),
        )
    }
}
