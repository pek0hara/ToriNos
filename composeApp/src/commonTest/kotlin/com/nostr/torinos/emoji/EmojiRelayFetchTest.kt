package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiRelayFetchTest {
    private val me = "a".repeat(64)
    private val spec = SubscriptionSpec(
        id = "test",
        filters = listOf(NostrFilter(kinds = listOf(10030), authors = listOf(me), limit = 1)),
        behavior = SubscriptionBehavior.Fetch(8_000),
    )

    @Test
    fun waitsForEveryRelayInsteadOfTheFirstEose() = runTest {
        val old = preferences("old", createdAt = 100)
        val new = preferences("new", createdAt = 200)
        val session = FakeSession(flow {
            emit(SubscriptionSignal.Event("fast", old, false))
            emit(SubscriptionSignal.Eose("fast"))
            delay(500)
            emit(SubscriptionSignal.Event("slow", new, false))
            emit(SubscriptionSignal.Eose("slow"))
            emit(SubscriptionSignal.FetchCompleted(mapOf("fast" to RelayOutcome.Eose, "slow" to RelayOutcome.Eose), false))
        })

        val result = fetchEventsFromRelays(spec, open = { session }) { true }

        assertEquals(listOf("old", "new"), result.events.map { it.id })
        assertTrue(result.complete)
        assertTrue(session.closed)
    }

    @Test
    fun timeoutReturnsWhatArrived() = runTest {
        val old = preferences("old", createdAt = 100)
        val session = FakeSession(flow {
            emit(SubscriptionSignal.Event("fast", old, false))
            emit(SubscriptionSignal.Eose("fast"))
            awaitCancellation()
        })

        val result = fetchEventsFromRelays(spec, open = { session }) { true }

        assertEquals(listOf("old"), result.events.map { it.id })
        assertFalse(result.complete)
        assertTrue(session.closed)
    }

    @Test
    fun preferencesTransportPicksTheNewestAcrossRelays() = runTest {
        val transport = NostrEmojiPreferenceTransport(
            sessionId = "session",
            relaysLoaded = {},
            writableRelayUrls = { emptyList() },
            open = { spec ->
                FakeSession(flow {
                    emit(SubscriptionSignal.Event("fast", preferences("old", createdAt = 100), false))
                    emit(SubscriptionSignal.Eose("fast"))
                    delay(300)
                    emit(SubscriptionSignal.Event("slow", preferences("new", createdAt = 200), false))
                    emit(SubscriptionSignal.FetchCompleted(mapOf("fast" to RelayOutcome.Eose, "slow" to RelayOutcome.Eose), false))
                })
            },
        )

        assertEquals("new", transport.fetchLatestPreferences(me)?.id)
    }

    @Test
    fun setReferenceFilterLeavesRoomForEveryAuthorAndIdentifierPair() {
        val a = "b".repeat(64)
        val b = "c".repeat(64)
        val filter = emojiSetReferenceFilter(
            listOf(EmojiSetAddress(a, "x"), EmojiSetAddress(b, "y"), EmojiSetAddress(a, "z")),
        )
        assertEquals(listOf(a, b), filter.authors)
        assertEquals(listOf("x", "y", "z"), filter.dTags)
        assertEquals(6, filter.limit)

        val many = (1..30).map { EmojiSetAddress("$it".padStart(64, '0'), "d$it") }
        assertEquals(500, emojiSetReferenceFilter(many).limit)
    }

    private fun preferences(id: String, createdAt: Long) =
        NostrEvent(id, me, createdAt, 10030, emptyList(), "", "sig")

    private class FakeSession(override val signals: Flow<SubscriptionSignal>) : SubscriptionSession {
        override val id = "test"
        var closed = false
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() { closed = true }
    }
}
