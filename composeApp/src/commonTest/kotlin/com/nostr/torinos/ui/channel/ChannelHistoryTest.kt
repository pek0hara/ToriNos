package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.ChannelReadingPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelHistoryTest {
    @Test
    fun cachedMessagesRemainWhenLatestPageIsCommittedAsOneBatch() = runTest {
        val response = CompletableDeferred<ChannelHistoryPage>()
        val requests = mutableListOf<NostrFilter>()
        val history = ChannelHistory(backgroundScope, "channel", { requests += it; response.await() }, { null })
        history.initialize(listOf(event(1)), null)
        runCurrent()
        assertEquals(listOf(event(1)), history.state.value.messages)
        assertEquals(30, requests.single().limit)
        assertNull(requests.single().since)
        history.receive(event(103))
        assertEquals(listOf(event(1)), history.state.value.messages)
        response.complete(ChannelHistoryPage(listOf(event(101), event(102)), true))
        runCurrent()
        assertEquals(listOf(103L, 102L, 101L, 1L), history.state.value.messages.map { it.createdAt })
        assertEquals(ChannelHistoryGap("101", "1"), history.state.value.gap)
        assertEquals("103", history.state.value.navigation?.messageId)
        assertFalse(history.state.value.isLoading)
        history.close()
    }

    @Test
    fun previousPositionFetchesItsWindowDirectlyAndExposesGap() = runTest {
        val requests = mutableListOf<NostrFilter>()
        val history = ChannelHistory(backgroundScope, "channel", {
            requests += it
            ChannelHistoryPage(if (it.until == null) events(100, 71) else events(20, 1), true)
        }, { null })
        history.initialize(emptyList(), ChannelReadingPosition("20", 20, 37))
        runCurrent()
        history.previous()
        runCurrent()
        assertEquals(2, requests.size)
        assertEquals(20, requests.last().until)
        assertEquals(ChannelScrollRequest(2, "20", 37), history.state.value.navigation)
        assertEquals(ChannelNavigationTarget.Previous, history.state.value.navigationTarget)
        assertEquals(ChannelHistoryGap("71", "20"), history.state.value.gap)
        assertTrue(history.state.value.hasPreviousPosition)
        history.close()
    }

    @Test
    fun readingOlderMessagesDoesNotAutoScrollForLiveEvents() = runTest {
        val history = ChannelHistory(backgroundScope, "channel", { ChannelHistoryPage(events(100, 71), true) }, { null })
        history.initialize(emptyList(), null)
        runCurrent()
        history.consumeNavigation(history.state.value.navigation!!.sequence)
        history.setAtLatest(false)
        history.receive(event(101))
        assertNull(history.state.value.navigation)
        assertEquals(1, history.state.value.newMessageCount)
        history.setAtLatest(true)
        assertEquals(0, history.state.value.newMessageCount)
        history.receive(event(102))
        assertEquals("102", history.state.value.navigation?.messageId)
        history.close()
    }

    @Test
    fun previousPostMissingUsesNearbyTimestampAndExplainsIt() = runTest {
        val history = ChannelHistory(backgroundScope, "channel", {
            ChannelHistoryPage(if (it.until == null) events(100, 71) else events(19, 1), true)
        }, { null })
        history.initialize(emptyList(), ChannelReadingPosition("deleted", 20, 99))
        runCurrent()
        history.previous()
        runCurrent()
        assertEquals("19", history.state.value.navigation?.messageId)
        assertEquals(0, history.state.value.navigation?.offset)
        assertNotNull(history.state.value.notice)
        history.close()
    }

    @Test
    fun gapIsFetchedFromNewerBoundaryAndRemovedOnlyWhenComplete() = runTest {
        val requests = mutableListOf<NostrFilter>()
        val history = ChannelHistory(backgroundScope, "channel", {
            requests += it
            ChannelHistoryPage(when {
                it.until == null -> events(100, 71)
                it.since != null && it.until == 71L -> events(71, 41)
                it.since != null -> events(41, 20)
                else -> events(20, 1)
            }, true)
        }, { null })
        history.initialize(emptyList(), ChannelReadingPosition("20", 20))
        runCurrent()
        history.previous()
        runCurrent()
        history.fillGap()
        runCurrent()
        assertEquals(20, requests.last().since)
        assertEquals(ChannelHistoryGap("41", "20"), history.state.value.gap)
        history.fillGap()
        runCurrent()
        assertNull(history.state.value.gap)
        assertEquals(100, history.state.value.messages.size)
        history.close()
    }

    @Test
    fun failedInitialPageRetainsCacheAndCanBeRetried() = runTest {
        var calls = 0
        val history = ChannelHistory(backgroundScope, "channel", {
            calls++
            ChannelHistoryPage(if (calls == 1) listOf(event(99)) else events(100, 71), calls > 1)
        }, { null })
        history.initialize(listOf(event(1)), null)
        runCurrent()
        assertNotNull(history.state.value.error)
        assertEquals(listOf(99L, 1L), history.state.value.messages.map { it.createdAt })
        assertFalse(history.state.value.canLoadOlder)
        history.retry()
        runCurrent()
        assertNull(history.state.value.error)
        assertEquals(events(100, 71) + event(1), history.state.value.messages)
        assertEquals(ChannelHistoryGap("99", "1"), history.state.value.gap)
        history.close()
    }

    @Test
    fun successfulLatestRetryDoesNotDiscardAlreadyLoadedPosts() = runTest {
        var calls = 0
        val history = ChannelHistory(backgroundScope, "channel", {
            calls++
            when (calls) {
                1 -> ChannelHistoryPage(events(100, 71), false)
                else -> ChannelHistoryPage(events(105, 76), true)
            }
        }, { null })
        history.initialize(events(70, 41), null)
        runCurrent()
        assertEquals(60, history.state.value.messages.size)

        history.latest()
        runCurrent()

        assertEquals(65, history.state.value.messages.size)
        assertEquals("105", history.state.value.messages.first().id)
        assertEquals("41", history.state.value.messages.last().id)
        assertEquals(ChannelHistoryGap("71", "70"), history.state.value.gap)
        assertEquals("105", history.state.value.navigation?.messageId)
        history.close()
    }

    @Test
    fun failedOlderPageDoesNotAdvanceCursorAndSameSecondIsIncluded() = runTest {
        val requests = mutableListOf<NostrFilter>()
        val history = ChannelHistory(backgroundScope, "channel", {
            requests += it
            when (requests.size) {
                1 -> ChannelHistoryPage(events(100, 71), true)
                2 -> ChannelHistoryPage(listOf(event(60)), false)
                else -> ChannelHistoryPage(listOf(event(71).copy(id = "same-second")) + events(71, 41), true)
            }
        }, { null })
        history.initialize(emptyList(), null)
        runCurrent()
        history.older()
        runCurrent()
        assertNotNull(history.state.value.error)
        history.retry()
        runCurrent()
        assertEquals(71, requests[1].until)
        assertEquals(71, requests[2].until)
        assertEquals(31, requests[2].limit)
        assertTrue(history.state.value.messages.any { it.id == "same-second" })
        assertEquals(1, history.state.value.messages.count { it.id == "60" })
        history.close()
    }

    @Test
    fun concurrentNavigationDoesNotStartAnotherFetchAndClosingCancelsRequest() = runTest {
        var calls = 0
        var cancelled = false
        val history = ChannelHistory(backgroundScope, "channel", {
            calls++
            try { awaitCancellation() } finally { cancelled = true }
        }, { null })
        history.initialize(emptyList(), ChannelReadingPosition("20", 20))
        runCurrent()
        history.previous()
        history.latest()
        history.older()
        runCurrent()
        assertEquals(1, calls)
        history.close()
        runCurrent()
        assertTrue(cancelled)
    }

    @Test
    fun closeIgnoresLateLiveEventAndKeepsPublishedState() = runTest {
        val history = ChannelHistory(
            backgroundScope,
            "channel",
            { ChannelHistoryPage(listOf(event(10)), true) },
            { null },
        )
        history.initialize(emptyList(), null)
        runCurrent()
        val beforeClose = history.state.value

        history.close()
        history.receive(event(20))

        assertEquals(beforeClose, history.state.value)
    }

    @Test
    fun knownPositionIsFoundByIdEvenWhenSameSecondFillsPage() = runTest {
        val saved = event(20).copy(id = "saved")
        val lookedUp = mutableListOf<String>()
        val history = ChannelHistory(backgroundScope, "channel", {
            ChannelHistoryPage(if (it.until == null) events(100, 71) else
                (1..30).map { index -> event(20).copy(id = "peer-$index") }, true)
        }, { lookedUp += it; saved })
        history.initialize(emptyList(), ChannelReadingPosition("saved", 20, 15))
        runCurrent()
        history.previous()
        runCurrent()
        assertEquals(listOf("saved"), lookedUp)
        assertEquals("saved", history.state.value.navigation?.messageId)
        assertEquals(15, history.state.value.navigation?.offset)
        assertNull(history.state.value.notice)
        history.close()
    }

    @Test
    fun latestNavigationUsesLoadedMessagesWithoutFetchingOrRemovingGap() = runTest {
        val requests = mutableListOf<NostrFilter>()
        val history = ChannelHistory(backgroundScope, "channel", {
            requests += it
            ChannelHistoryPage(if (it.until == null) events(100, 71) else events(20, 1), true)
        }, { null })
        history.initialize(emptyList(), ChannelReadingPosition("20", 20))
        runCurrent()
        history.consumeNavigation(history.state.value.navigation!!.sequence)
        history.previous()
        runCurrent()
        history.consumeNavigation(history.state.value.navigation!!.sequence)
        val gap = history.state.value.gap
        history.latest()
        runCurrent()
        assertEquals(2, requests.size)
        assertFalse(history.state.value.isLoading)
        assertEquals("100", history.state.value.navigation?.messageId)
        assertEquals(gap, history.state.value.gap)
        history.close()
    }

    @Test
    fun mutedGapEdgeDoesNotHideTheGapOrShiftItToNewerMessages() {
        val raw = events(100, 71) + events(20, 1)
        val state = ChannelHistoryState(messages = raw, gap = ChannelHistoryGap("71", "20"))
        val visible = raw.filterNot { it.id == "20" || it.id == "71" }
        assertEquals(29, state.gapIndex(visible))
        assertEquals(30, state.gapIndex(events(100, 71)))
    }

    @Test
    fun longHistoryKeepsLatestAndViewportAreaWithinLimit() = runTest {
        var newest = 1_000
        val requests = mutableListOf<NostrFilter>()
        val history = ChannelHistory(backgroundScope, "channel", {
            requests += it
            val count = it.limit ?: ChannelHistory.PAGE_SIZE
            val page = events(newest, newest - count + 1)
            newest -= count
            ChannelHistoryPage(page, true)
        }, { null })
        history.initialize(emptyList(), null)
        runCurrent()
        repeat(17) {
            history.consumeNavigation(history.state.value.navigation?.sequence ?: -1)
            history.setAtLatest(false)
            history.setViewport(history.state.value.messages.last().id)
            history.older()
            runCurrent()
        }

        assertEquals(ChannelHistory.MAX_VISIBLE_MESSAGES, history.state.value.messages.size)
        assertEquals("1000", history.state.value.messages.first().id)
        assertNotNull(history.state.value.gap)
        assertTrue(history.state.value.canLoadOlder)
        val requestCount = requests.size
        history.latest()
        runCurrent()
        assertEquals(requestCount, requests.size)
        assertEquals("1000", history.state.value.navigation?.messageId)
        assertTrue(history.state.value.navigation?.animated == true)
        assertEquals(ChannelNavigationTarget.Latest, history.state.value.navigationTarget)
        history.consumeNavigation(history.state.value.navigation!!.sequence)
        assertNull(history.state.value.navigationTarget)
        history.close()
    }

    @Test
    fun latestButtonVisibilityFollowsWhetherNewestMessageIsOnScreen() {
        assertTrue(isChannelLatestVisible("latest", listOf("latest", "older"), 20))
        assertFalse(isChannelLatestVisible("latest", listOf("older", "oldest"), 20))
        assertTrue(isChannelLatestVisible("latest", emptyList(), 0))
    }

    @Test
    fun olderPostsAutoLoadOnlyForUserScrollNearUnrequestedEdge() {
        val messages = events(100, 71)
        val ready = ChannelHistoryState(messages = messages, isLoading = false, canLoadOlder = true)
        val nearOldest = setOf("73", "72", "71")

        assertTrue(shouldAutoLoadOlder(ready, messages, nearOldest, true, false, null))
        assertFalse(shouldAutoLoadOlder(ready, messages, nearOldest, false, false, null))
        assertFalse(shouldAutoLoadOlder(ready, messages, nearOldest, true, true, null))
        assertFalse(shouldAutoLoadOlder(ready, messages, nearOldest, true, false, "71"))
        assertFalse(shouldAutoLoadOlder(ready.copy(isLoading = true), messages, nearOldest, true, false, null))
        assertFalse(shouldAutoLoadOlder(ready.copy(error = "timeout"), messages, nearOldest, true, false, null))
        assertFalse(shouldAutoLoadOlder(ready, messages, setOf("90"), true, false, null))
    }

    @Test
    fun completePageWithoutNewOlderPostsStopsPagination() = runTest {
        var calls = 0
        val history = ChannelHistory(backgroundScope, "channel", {
            calls++
            ChannelHistoryPage(if (calls == 1) events(100, 71) else listOf(event(71)), true)
        }, { null })
        history.initialize(emptyList(), null)
        runCurrent()
        history.older()
        runCurrent()

        assertFalse(history.state.value.canLoadOlder)
        assertEquals(30, history.state.value.messages.size)
        history.close()
    }

    private fun event(time: Long) = NostrEvent(time.toString(), "author", time, 42,
        listOf(listOf("e", "channel", "", "root")), "message", "sig")
    private fun events(from: Int, to: Int) = (from downTo to).map { event(it.toLong()) }
}
