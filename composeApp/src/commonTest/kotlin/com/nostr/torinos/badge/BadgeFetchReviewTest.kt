package com.nostr.torinos.badge

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Regression tests for the fetch review in docs/badge-feature-design.md §12. */
@OptIn(ExperimentalCoroutinesApi::class)
class BadgeFetchReviewTest {
    private val one = "wss://one.example"
    private val dead = "wss://dead.example"
    private val data get() = listOf(badgeEvent(10008, tags = badgePair().tags), badgeAward(), badgeDefinition())
    private fun hex(i: Int) = i.toString(16).padStart(64, '0')

    /** Each relay answers with its own events (newest first, per-relay `limit`); `null` means unreachable. */
    private fun relays(spec: SubscriptionSpec, byRelay: (String) -> List<NostrEvent>?, wait: (String) -> Long = { 0 }) = object : SubscriptionSession {
        override val id = spec.id
        override val signals = flow {
            val urls = (spec.target as RelayTarget.Explicit).urls
            val outcomes = mutableMapOf<String, RelayOutcome>()
            urls.sortedBy(wait).forEach { url ->
                delay(wait(url))
                val events = byRelay(url)
                if (events == null) { outcomes[url] = RelayOutcome.Unavailable("down"); return@forEach }
                spec.filters.forEach { filter ->
                    events.filter { matchesBadgeFilter(it, filter) }.sortedByDescending { it.createdAt }
                        .let { list -> filter.limit?.let { list.take(it) } ?: list }
                        .forEach { emit(SubscriptionSignal.Event(url, it, false)) }
                }
                outcomes[url] = RelayOutcome.Eose
            }
            emit(SubscriptionSignal.FetchCompleted(outcomes, false))
        }
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() = Unit
    }

    private fun TestScope.transport(open: (SubscriptionSpec) -> SubscriptionSession) =
        BadgeTransport(open = open, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler))

    @Test fun unreachableRelayStillReusesFoundReferencesAndIsNotAnError() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val repo = BadgeRepository(backgroundScope, transport { spec -> requests += spec; relays(spec, { if (it == dead) null else data }) },
            { setOf(one, dead) }, nowMillis = { testScheduler.currentTime })
        assertFalse(repo.refresh(setOf(badgeOwner)).complete)
        val awards = requests.count { it.filters.any { f -> f.kinds == listOf(8) } }
        val definitions = requests.count { it.filters.any { f -> f.kinds == listOf(30009) } }
        repo.refresh(setOf(badgeOwner))
        // The award ID was found and a definition was returned, so neither is queried again.
        assertEquals(awards, requests.count { it.filters.any { f -> f.kinds == listOf(8) } })
        assertEquals(definitions, requests.count { it.filters.any { f -> f.kinds == listOf(30009) } })
        val state = repo.observe(badgeOwner).value
        assertEquals("Badge", state.badges.single().name)
        assertTrue(state.partial)
        assertNull(state.error)
        // Forced confirmation still re-checks the partially covered definition.
        repo.refresh(setOf(badgeOwner), forceDependencies = true)
        assertTrue(requests.count { it.filters.any { f -> f.kinds == listOf(30009) } } > definitions)
        repo.close()
    }

    @Test fun noRelayAnsweringIsReportedAsFailure() = runTest {
        val repo = BadgeRepository(backgroundScope, transport { relays(it, { null }) }, { setOf(one, dead) })
        assertFalse(repo.refresh(setOf(badgeOwner)).complete)
        assertNotNull(repo.observe(badgeOwner).value.error)
        repo.close()
    }

    @Test fun awardPagesAdvancePerRelaySoDenseRelaysAreNotSkipped() = runTest {
        fun award(i: Int, time: Long) = badgeEvent(8, badgeIssuer, listOf(listOf("a", badgeAddress.value), listOf("p", badgeOwner)), time = time, id = hex(i))
        val dense = (1..150).map { award(it, 1000L + it) }        // 1001..1150
        val sparse = (1..100).map { award(1000 + it, 10L * it) }  // 10..1000
        val untils = mutableListOf<Long?>()
        val repo = BadgeRepository(backgroundScope, transport { spec ->
            spec.filters.firstOrNull { it.pTags != null }?.let { untils += it.until }
            relays(spec, { url -> if (url == one) dense + badgeDefinition() else sparse })
        }, { setOf(one) })
        val signer = object : AccountSigner {
            override val pubkey = badgeOwner
            override fun encryptToSelf(plaintext: String) = plaintext
            override fun decrypt(content: String, peerPubkey: String) = content
            override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent = error("unused")
        }
        val store = AccountBadgeStore(badgeOwner, "session", signer, backgroundScope, repo,
            relays = { setOf(one, "wss://two.example") }, writableRelays = { emptySet() }, ensureActive = {},
            readStorage = { null }, writeStorage = { _, _ -> }, validate = { true }, now = { 100 })
        store.loadAwards()
        assertTrue(store.state.value.canLoadMore)
        store.loadAwards(reset = false)
        store.loadAwards(reset = false)
        // The sparse relay's oldest (10) must not be used while the dense relay still has 1001..1050.
        assertEquals(listOf(null, 1051L, 10L), untils)
        assertFalse(store.state.value.canLoadMore)
        repo.close()
    }

    @Test fun relayCappingARaisedLimitIsStillPaged() = runTest {
        fun award(i: Int, time: Long) = badgeEvent(8, badgeIssuer, listOf(listOf("a", badgeAddress.value), listOf("p", badgeOwner)), time = time, id = hex(i))
        // 150 awards in one second force a raised limit; the relay serves at most 120 per request.
        val sameSecond = (1..150).map { award(it, 500) } + (151..160).map { award(it, (400 - it).toLong()) }
        val untils = mutableListOf<Long?>()
        val repo = BadgeRepository(backgroundScope, transport { spec ->
            val filter = spec.filters.firstOrNull { it.pTags != null }
            filter?.let { untils += it.until }
            val capped = filter?.let { spec.copy(filters = listOf(it.copy(limit = minOf(it.limit ?: 120, 120)))) } ?: spec
            relays(capped, { sameSecond + badgeDefinition() })
        }, { setOf(one) })
        val signer = object : AccountSigner {
            override val pubkey = badgeOwner
            override fun encryptToSelf(plaintext: String) = plaintext
            override fun decrypt(content: String, peerPubkey: String) = content
            override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent = error("unused")
        }
        val store = AccountBadgeStore(badgeOwner, "session", signer, backgroundScope, repo,
            relays = { setOf(one) }, writableRelays = { emptySet() }, ensureActive = {},
            readStorage = { null }, writeStorage = { _, _ -> }, validate = { true }, now = { 100 })
        store.loadAwards()                 // 100 at second 500
        store.loadAwards(reset = false)    // until=500, limit 100 → still second 500, raise limit
        store.loadAwards(reset = false)    // until=500, limit 200 → capped at 120, must not stop here
        assertTrue(store.state.value.canLoadMore)
        assertEquals(listOf(null, 500L, 500L), untils)
        repo.close()
    }

    @Test fun forcedRequestDuringFetchRunsAfterIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        var selections = 0
        val repo = BadgeRepository(backgroundScope, transport { spec ->
            if (spec.filters.any { it.kinds == listOf(10008) }) selections++
            val first = selections == 1 && spec.filters.any { it.kinds == listOf(10008) }
            object : SubscriptionSession {
                override val id = spec.id
                override val signals = flow {
                    if (first) gate.await()
                    emit(SubscriptionSignal.FetchCompleted(mapOf(one to RelayOutcome.Eose), false))
                }
                override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
                override suspend fun close() = Unit
            }
        }, { setOf(one) }, nowMillis = { testScheduler.currentTime })
        val release = repo.acquire(badgeOwner)
        advanceTimeBy(101); runCurrent()
        assertEquals(1, selections)
        repo.resolveMore(badgeOwner)
        advanceTimeBy(101); runCurrent()
        assertEquals(1, selections)
        gate.complete(Unit)
        advanceTimeBy(101); runCurrent()
        assertEquals(2, selections)
        release(); repo.close()
    }

    @Test fun discoveredOutboxIsQueriedBeforeKnownRelaysFinish() = runTest {
        val outbox = "wss://outbox.example"
        val relayList = badgeEvent(10002, tags = listOf(listOf("r", outbox, "write")), id = hex(77))
        val opened = mutableListOf<SubscriptionSpec>()
        val repo = BadgeRepository(backgroundScope, transport { spec ->
            opened += spec
            relays(spec, { data + relayList }, wait = { url -> if (url == one && spec.filters.any { it.kinds == listOf(10008) }) 9_000 else 0 })
        }, { setOf(one) }, nowMillis = { testScheduler.currentTime })
        val fetch = backgroundScope.async { repo.refresh(setOf(badgeOwner)) }
        advanceTimeBy(500); runCurrent()
        assertTrue(opened.any { spec -> spec.filters.any { it.kinds == listOf(10008) } &&
            (spec.target as RelayTarget.Explicit).urls.contains(outbox) })
        assertFalse(fetch.isCompleted)
        advanceTimeBy(20_000); runCurrent()
        assertTrue(fetch.await().complete)
        repo.close()
    }

    @Test fun cardBadgeListIgnoresLoadingAndReferenceUpdates() = runTest {
        val repo = BadgeRepository(backgroundScope, transport { relays(it, { data }) }, { setOf(one) }, nowMillis = { testScheduler.currentTime })
        val lists = mutableListOf<List<BadgeDisplayItem>>()
        val states = mutableListOf<BadgeProfileState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.badgeList(badgeOwner).collect { lists += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.observe(badgeOwner).collect { states += it } }
        repo.refresh(setOf(badgeOwner))
        val before = states.size
        repo.refresh(setOf(badgeOwner))
        // The full state still changes (loading, partial) but the card's list does not.
        assertTrue(states.size > before)
        assertEquals(listOf(0, 1), lists.map { it.size })
        repo.close()
    }

    @Test fun evictionKeepsEventsOfDisplayedAuthors() = runTest {
        val repo = BadgeRepository(backgroundScope, transport { relays(it, { emptyList() }) }, { setOf(one) })
        repo.ingest(data)
        val release = repo.acquire(badgeOwner)
        assertEquals(1, repo.observe(badgeOwner).value.badges.size)
        repo.ingest((1..5_001).map { badgeEvent(8, badgeIssuer, listOf(listOf("p", badgeIssuer)), id = hex(10_000 + it)) })
        assertEquals(1, repo.observe(badgeOwner).value.badges.size)
        assertTrue(repo.events().size <= 5_000)
        release(); repo.close()
    }

    @Test fun droppedTombstoneAlsoDropsTheDeletedEvent() = runTest {
        val repo = BadgeRepository(backgroundScope, transport { relays(it, { emptyList() }) }, { setOf(one) })
        val award = badgeAward()
        repo.ingest(listOf(award, badgeEvent(5, badgeIssuer, listOf(listOf("e", award.id)), id = hex(1))))
        assertTrue(repo.events().any { it.id == award.id })
        repo.ingest((2..5_001).map { badgeEvent(5, badgeIssuer, listOf(listOf("e", hex(90_000 + it))), id = hex(it)) })
        assertTrue(repo.events().none { it.id == award.id })
        repo.close()
    }
}
