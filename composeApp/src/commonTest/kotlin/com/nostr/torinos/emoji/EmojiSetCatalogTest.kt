package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiSetCatalogTest {
    private val relay = "wss://one.example"
    private val author = "c".repeat(64)
    private val found = EmojiSetAddress(author, "found")
    private val missing = EmojiSetAddress(author, "missing")
    private val foundEvent = NostrEvent(
        "found-id", author, 1, EmojiSetAddress.KIND_EMOJI_SET,
        listOf(listOf("d", "found"), listOf("emoji", "cat", "https://example.com/cat.png")), "", "sig",
    )

    @Test
    fun referencesTheRelayReportedMissingAreNotRequestedAgain() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val catalog = catalog { spec ->
            requests += spec
            response(spec, if (spec.filters.any { it.dTags != null }) listOf(foundEvent) else emptyList())
        }
        loadList(catalog)

        catalog.fetchReferences(listOf(found, missing))
        catalog.fetchReferences(listOf(found, missing))

        assertEquals(2, requests.size, "一覧1回と参照1回だけ")
        assertEquals(listOf(found), catalog.state.value.publishedSets.map { it.address })
        assertEquals(setOf(missing), catalog.state.value.failedReferences)
    }

    @Test
    fun referencesMissingFromAnIncompleteResponseAreRequestedAgain() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val catalog = catalog { spec ->
            requests += spec
            // 一覧は正常に返し、参照の取得だけ時間切れにする。
            response(spec, emptyList(), timedOut = spec.filters.any { it.dTags != null })
        }
        loadList(catalog)

        catalog.fetchReferences(listOf(missing))
        catalog.fetchReferences(listOf(missing))

        assertEquals(3, requests.size)
        assertTrue(catalog.state.value.failedReferences.isEmpty())
    }

    @Test
    fun refreshRetriesReferencesThatWereMissing() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val catalog = catalog { spec -> requests += spec; response(spec, emptyList()) }
        loadList(catalog)
        catalog.fetchReferences(listOf(missing))

        catalog.refresh()
        advanceUntilIdle()
        catalog.fetchReferences(listOf(missing))

        assertEquals(listOf(missing), requests.last().filters.map { EmojiSetAddress(it.authors!!.single(), it.dTags!!.single()) })
        assertEquals(4, requests.size, "一覧2回と参照2回")
    }

    @Test
    fun listLoadedIsSetOnlyAfterTheListFetchEnds() = runTest {
        val catalog = catalog { spec -> response(spec, emptyList(), delayMillis = 100) }
        assertFalse(catalog.state.value.listLoaded)
        catalog.acquire(relay)
        testScheduler.runCurrent()
        assertFalse(catalog.state.value.listLoaded)
        advanceUntilIdle()
        assertTrue(catalog.state.value.listLoaded)

        val failing = catalog { spec -> response(spec, emptyList(), timedOut = true) }
        failing.acquire(relay)
        advanceUntilIdle()
        assertTrue(failing.state.value.listLoaded)
    }

    @Test
    fun referenceBatchesAreFetchedConcurrently() = runTest {
        val catalog = catalog { spec -> response(spec, emptyList(), delayMillis = if (spec.filters.any { it.dTags != null }) 100 else 0) }
        loadList(catalog)
        val references = (1..61).map { EmojiSetAddress(author, "set$it") }

        val startedAt = testScheduler.currentTime
        catalog.fetchReferences(references)

        // 20件ずつ4バッチを同時3本まで: 2往復分。
        assertEquals(200, testScheduler.currentTime - startedAt)
        assertEquals(references.toSet(), catalog.state.value.failedReferences)
    }

    private fun TestScope.catalog(open: (SubscriptionSpec) -> SubscriptionSession) = EmojiSetCatalog(
        transport = EmojiDiscoveryTransport(open = { open(it) }, validate = { true }),
        scope = this,
        selectedRelay = { relay },
        now = { 0L },
    )

    private fun TestScope.loadList(catalog: EmojiSetCatalog) {
        catalog.acquire(relay)
        advanceUntilIdle()
    }

    private fun response(
        spec: SubscriptionSpec,
        events: List<NostrEvent>,
        timedOut: Boolean = false,
        delayMillis: Long = 0,
    ) =
        object : SubscriptionSession {
            override val id = spec.id
            override val signals = flow {
                delay(delayMillis)
                val url = (spec.target as RelayTarget.Single).url
                events.filter { event -> spec.filters.any { it.acceptsEmojiDiscoveryEvent(event) } }
                    .forEach { emit(SubscriptionSignal.Event(url, it, false)) }
                emit(SubscriptionSignal.FetchCompleted(
                    mapOf(url to if (timedOut) RelayOutcome.TimedOut else RelayOutcome.Eose), timedOut))
            }
            override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
            override suspend fun close() = Unit
        }
}
