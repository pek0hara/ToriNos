package com.nostr.torinos.journal

import com.nostr.torinos.journal.JournalActivityKind.Post
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalEventSourceTest {
    private val clock = utcClock()
    private val sep1 = LocalDate(2026, 9, 1)
    private val sep2 = LocalDate(2026, 9, 2)

    private val request = JournalFetchPlanner.forRange(
        listOf(sep1, sep2), JournalCoverage(), setOf(Post), setOf(OWNER), null, clock,
    ).single()

    @Test
    fun aRelayThatReachedItsLimitCutsTheResultAtItsOldestEvent() = runTest {
        val newer = post("newer", createdAt = epochOf(sep2))
        val older = post("older", createdAt = epochOf(sep1, hour = 20))
        val opened = mutableListOf<SubscriptionSpec>()
        val source = NostrJournalEventSource(
            openSession = { spec ->
                opened += spec
                FakeSession(
                    SubscriptionSignal.Event("wss://a", newer, isLive = false),
                    SubscriptionSignal.Event("wss://a", older, isLive = false),
                    SubscriptionSignal.Event("wss://b", newer, isLive = false),
                    SubscriptionSignal.FetchCompleted(
                        mapOf("wss://a" to RelayOutcome.Eose, "wss://b" to RelayOutcome.Eose),
                        timedOut = false,
                    ),
                )
            },
            maxLimit = { relayUrl -> if (relayUrl == "wss://a") 2 else null },
        )

        val result = source.fetch(request)

        // 件数をリレーごとに数えるため、重複排除とキャッシュの再生を切る。
        assertFalse(opened.single().deduplicateEvents)
        assertFalse(opened.single().replayCachedEvents)
        assertEquals(listOf("newer", "older"), result.events.map { it.id })
        assertTrue(result.complete)
        assertEquals(older.createdAt, result.truncatedAt)
    }

    @Test
    fun relaysBelowTheirLimitDoNotCutTheResult() = runTest {
        val source = NostrJournalEventSource(
            openSession = {
                FakeSession(
                    SubscriptionSignal.Event("wss://a", post("one", createdAt = epochOf(sep1)), isLive = false),
                    SubscriptionSignal.FetchCompleted(mapOf("wss://a" to RelayOutcome.Eose), timedOut = false),
                )
            },
            maxLimit = { null },
        )

        assertNull(source.fetch(request).truncatedAt)
    }

    private class FakeSession(vararg signals: SubscriptionSignal) : SubscriptionSession {
        override val id: String = "fake"
        override val signals: Flow<SubscriptionSignal> = flowOf(*signals)
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() = Unit
    }
}
