package com.nostr.torinos.journal

import com.nostr.torinos.network.RelayTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalReferenceSourceTest {
    private val selected = "wss://selected"
    private val other = "wss://other"
    private val another = "wss://another"

    @Test
    fun asksOnlyTheSelectedRelayWhenItHasEverything() = runTest {
        val relays = FakeRelays(selected to setOf(hexId(1), hexId(2)))
        val received = mutableListOf<String>()

        relays.source().fetch(setOf(hexId(1), hexId(2)), selected) { received += it.id }

        assertEquals(listOf<Request>(RelayTarget.Single(selected) to setOf(hexId(1), hexId(2))), relays.requests)
        assertEquals(setOf(hexId(1), hexId(2)), received.toSet())
    }

    @Test
    fun asksTheOtherRelaysOnlyForWhatTheSelectedRelayLacks() = runTest {
        val relays = FakeRelays(selected to setOf(hexId(1)), other to setOf(hexId(2)))
        val received = mutableListOf<String>()

        relays.source().fetch(setOf(hexId(1), hexId(2)), selected) { received += it.id }

        assertEquals<List<Request>>(
            listOf(
                RelayTarget.Single(selected) to setOf(hexId(1), hexId(2)),
                RelayTarget.Explicit(setOf(other, another)) to setOf(hexId(2)),
            ),
            relays.requests,
        )
        assertEquals(setOf(hexId(1), hexId(2)), received.toSet())
    }

    @Test
    fun withoutASelectedRelayAsksEveryEnabledRelayOnce() = runTest {
        val relays = FakeRelays(other to setOf(hexId(1)))

        relays.source().fetch(setOf(hexId(1)), relayUrl = null) {}

        assertEquals(listOf<Request>(RelayTarget.AllEnabled to setOf(hexId(1))), relays.requests)
    }

    @Test
    fun doesNotFallBackWhenTheSelectedRelayIsTheOnlyOne() = runTest {
        val relays = FakeRelays(enabled = listOf(selected))

        relays.source().fetch(setOf(hexId(1)), selected) {}

        assertEquals(listOf<Request>(RelayTarget.Single(selected) to setOf(hexId(1))), relays.requests)
    }

    @Test
    fun splitsLargeRequestsIntoChunks() = runTest {
        val ids = (1..250).mapTo(mutableSetOf(), ::hexId)
        val relays = FakeRelays(selected to ids)

        relays.source(chunkSize = 100).fetch(ids, selected) {}

        assertEquals(listOf(100, 100, 50), relays.requests.map { it.second.size })
        assertTrue(relays.requests.all { it.first == RelayTarget.Single(selected) })
    }

    /** リレーごとに持っているIDだけを返す。[enabled]は有効なリレーの一覧。 */
    private inner class FakeRelays(
        vararg holdings: Pair<String, Set<String>>,
        private val enabled: List<String> = listOf(selected, other, another),
    ) {
        private val held = holdings.toMap()
        val requests = mutableListOf<Request>()

        fun source(chunkSize: Int = 200) = NostrJournalReferenceSource(
            fetchFrom = { ids, target, onEvent ->
                requests += target to ids
                val urls = when (target) {
                    RelayTarget.AllEnabled -> enabled
                    is RelayTarget.Single -> listOf(target.url)
                    is RelayTarget.Explicit -> target.urls.toList()
                }
                urls.flatMap { held[it].orEmpty() }.filter { it in ids }.distinct().forEach { id ->
                    onEvent(event(id, kind = 1))
                }
            },
            enabledRelayUrls = { enabled },
            chunkSize = chunkSize,
        )
    }
}

private typealias Request = Pair<RelayTarget, Set<String>>
