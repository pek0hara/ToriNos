package com.nostr.torinos.journal

import com.nostr.torinos.journal.JournalActivityKind.Post
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JournalEventSourceTest {
    private val FAST = "wss://fast"
    private val SLOW = "wss://slow"
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

    @Test
    fun relaysStillPendingOneSecondAfterTheFirstEoseAreNotWaitedFor() = runTest {
        val session = ScriptedSession(now = { testScheduler.currentTime })
        session.send(SubscriptionSignal.Event(FAST, post("fast", createdAt = epochOf(sep1)), isLive = false))
        session.send(SubscriptionSignal.Eose(FAST))
        session.onFinish = mapOf(FAST to RelayOutcome.Eose, SLOW to RelayOutcome.TimedOut)
        val source = NostrJournalEventSource(openSession = { session }, maxLimit = { null })

        val result = source.fetch(request)

        assertEquals(1_000L, session.finishedAt)
        assertEquals(listOf("fast"), result.events.map { it.id })
        assertTrue(result.complete)
        assertFalse(result.allRelaysFinished)
        assertEquals(setOf(SLOW), result.retryRelays)
    }

    @Test
    fun relaysThatAllFinishWithinTheWaitAreNotCutShort() = runTest {
        val session = ScriptedSession(now = { testScheduler.currentTime })
        session.send(SubscriptionSignal.Eose(FAST))
        val source = NostrJournalEventSource(openSession = { session }, maxLimit = { null })
        backgroundScope.launch {
            delay(300)
            session.send(SubscriptionSignal.Eose(SLOW))
            session.complete(mapOf(FAST to RelayOutcome.Eose, SLOW to RelayOutcome.Eose))
        }

        val result = source.fetch(request)

        assertNull(session.finishedAt)
        assertTrue(result.allRelaysFinished)
        assertEquals(300L, testScheduler.currentTime)
    }

    @Test
    fun withoutAnyEoseTheFetchWaitsForItsTimeout() = runTest {
        val session = ScriptedSession(now = { testScheduler.currentTime })
        val source = NostrJournalEventSource(openSession = { session }, maxLimit = { null })
        backgroundScope.launch {
            delay(8_000)
            session.complete(mapOf(FAST to RelayOutcome.TimedOut, SLOW to RelayOutcome.TimedOut), timedOut = true)
        }

        val result = source.fetch(request)

        assertNull(session.finishedAt)
        assertFalse(result.complete)
        assertEquals(setOf(FAST, SLOW), result.retryRelays)
    }

    @Test
    fun engagementAlsoStopsWaitingOneSecondAfterTheFirstEose() = runTest {
        val note = post(hexId(1))
        val session = ScriptedSession(now = { testScheduler.currentTime })
        session.send(SubscriptionSignal.Event(FAST, reaction("like", targetId = note.id), isLive = false))
        session.send(SubscriptionSignal.Eose(FAST))
        session.onFinish = mapOf(FAST to RelayOutcome.Eose, SLOW to RelayOutcome.TimedOut)
        val received = mutableListOf<String>()

        val status = NostrJournalEngagementSource(openSession = { session }).fetch(setOf(note.id), null) { received += it.id }

        assertEquals(1_000L, session.finishedAt)
        assertEquals(listOf("like"), received)
        assertEquals(JournalFetchStatus(complete = true, allRelaysFinished = false, retryRelays = setOf(SLOW)), status)
    }

    /** 信号を順に流す。[finishPendingRelays]が呼ばれたら[onFinish]の結果で完了する（`NostrRepository`と同じ）。 */
    private class ScriptedSession(private val now: () -> Long) : SubscriptionSession {
        private val channel = Channel<SubscriptionSignal>(Channel.UNLIMITED)
        var onFinish: Map<String, RelayOutcome> = emptyMap()
        var finishedAt: Long? = null
        override val id: String = "scripted"
        override val signals: Flow<SubscriptionSignal> = channel.receiveAsFlow()

        fun send(signal: SubscriptionSignal) {
            channel.trySend(signal)
        }

        fun complete(outcomes: Map<String, RelayOutcome>, timedOut: Boolean = false) {
            channel.trySend(SubscriptionSignal.FetchCompleted(outcomes, timedOut))
            channel.close()
        }

        override suspend fun finishPendingRelays() {
            finishedAt = now()
            complete(onFinish, timedOut = true)
        }

        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() {
            channel.close()
        }
    }

    private class FakeSession(vararg signals: SubscriptionSignal) : SubscriptionSession {
        override val id: String = "fake"
        override val signals: Flow<SubscriptionSignal> = flowOf(*signals)
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() = Unit
    }
}
