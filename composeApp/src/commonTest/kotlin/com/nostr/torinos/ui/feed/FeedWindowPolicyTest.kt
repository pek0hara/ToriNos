package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedWindowPolicyTest {
    private val events = (10 downTo 1).map { NostrEvent("$it", "author", it.toLong(), 1, emptyList(), "", "") }

    @Test fun windowKeepsInclusiveBoundaryAndAnchor() {
        val window = planFeedWindow(events, 3, emptyMap(), "8", maxEvents = 3)
        assertEquals(listOf("8", "7", "6"), window.events.map { it.id })
        assertTrue(window.hasOlder)
        assertTrue(window.hasNewer)
    }

    @Test fun missingAnchorFallsBackToLatestAndSortTimeControlsReveal() {
        val window = planFeedWindow(events, 10, mapOf("1" to 10), "missing")
        assertEquals(listOf("10", "1"), window.events.map { it.id })
        assertFalse(window.hasNewer)
        assertFalse(window.hasOlder)
    }

    @Test fun latestPendingStatePublishesOnceAndCannotPublishAfterClose() = runTest {
        val publisher = FeedStatePublisher(backgroundScope)
        publisher.set(FeedViewModel.UiState(newPostCount = 1), immediate = false)
        publisher.set(FeedViewModel.UiState(newPostCount = 2), immediate = false)
        assertEquals(0, publisher.state.value.newPostCount)
        advanceTimeBy(150); runCurrent()
        assertEquals(2, publisher.state.value.newPostCount)
        publisher.set(FeedViewModel.UiState(newPostCount = 3), immediate = false)
        publisher.close()
        advanceTimeBy(150); runCurrent()
        assertEquals(2, publisher.state.value.newPostCount)
    }
}
