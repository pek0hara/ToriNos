package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayFetchArbiterTest {
    @Test
    fun sameRelayFetchWaitsUntilPreviousSessionCloses() = runTest {
        val gateway = CountingGateway()
        val arbiter = RelayFetchArbiter()
        val first = arbiter.open("relay-a", gateway, spec("first", "relay-a"))
        val second = async { arbiter.open("relay-a", gateway, spec("second", "relay-a")) }
        val otherRelay = async { arbiter.open("relay-b", gateway, spec("other", "relay-b")) }
        runCurrent()
        // 別のリレーは待たない。同じリレーの次のREQは前の有限取得が閉じるまで送らない。
        assertTrue(otherRelay.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(listOf("first", "other"), gateway.opened)
        first.close()
        runCurrent()
        assertTrue(second.isCompleted)
        assertEquals(listOf("first", "other", "second"), gateway.opened)
        // 二重closeで後続のロックを解放しない。
        first.close()
        val third = async { arbiter.open("relay-a", gateway, spec("third", "relay-a")) }
        runCurrent()
        assertFalse(third.isCompleted)
        second.await().close()
        runCurrent()
        assertTrue(third.isCompleted)
    }

    private fun spec(id: String, url: String) = SubscriptionSpec(
        id = id,
        filters = listOf(NostrFilter(kinds = listOf(1))),
        target = RelayTarget.Single(url),
        behavior = SubscriptionBehavior.Fetch(1_000),
    )

    private class CountingGateway : FeedSubscriptionGateway {
        val opened = mutableListOf<String>()
        override val readableRelayUrls: Flow<Set<String>> = emptyFlow()
        override fun events(subscriptionId: String): Flow<NostrEvent> = emptyFlow()
        override suspend fun targetRelayUrls(target: RelayTarget): Set<String> = emptySet()
        override suspend fun open(spec: SubscriptionSpec): SubscriptionSession {
            opened += spec.id
            return object : SubscriptionSession {
                override val id = spec.id
                override val signals: Flow<SubscriptionSignal> = emptyFlow()
                override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
                override suspend fun close() = Unit
            }
        }
        override suspend fun subscribe(subscriptionId: String, filter: NostrFilter, target: RelayTarget) = emptySet<String>()
        override fun close(subscriptionId: String) = Unit
    }
}
