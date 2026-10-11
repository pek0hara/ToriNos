package com.nostr.torinos.ui.components

import com.nostr.torinos.ui.feed.FeedViewModel.InitialFeedState
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelinePlaceholderTest {
    @Test
    fun loadingWithoutEventsShowsSkeleton() {
        assertEquals(TimelinePlaceholder.Skeleton, timelinePlaceholder(hasEvents = false, InitialFeedState.Loading))
    }

    @Test
    fun slowLooksTheSameAsLoading() {
        assertEquals(TimelinePlaceholder.Skeleton, timelinePlaceholder(hasEvents = false, InitialFeedState.Slow))
    }

    @Test
    fun failedAndEmptyKeepTheirMessages() {
        assertEquals(TimelinePlaceholder.Failed, timelinePlaceholder(hasEvents = false, InitialFeedState.Failed))
        assertEquals(TimelinePlaceholder.Empty, timelinePlaceholder(hasEvents = false, InitialFeedState.Empty))
        assertEquals(TimelinePlaceholder.Empty, timelinePlaceholder(hasEvents = false, InitialFeedState.ContentReady))
    }

    @Test
    fun eventsAreShownWhateverTheState() {
        InitialFeedState.entries.forEach { state ->
            assertEquals(TimelinePlaceholder.None, timelinePlaceholder(hasEvents = true, state), "state=$state")
        }
    }
}
