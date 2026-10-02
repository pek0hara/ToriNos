package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BadgeFetchEfficiencyTest {
    private val relays = setOf("wss://one.example")
    private val data get() = listOf(badgeEvent(10008, tags = badgePair().tags), badgeAward(), badgeDefinition())
    private fun delayed(spec: SubscriptionSpec, events: List<NostrEvent>, wait: Long, complete: Boolean = true) = object : SubscriptionSession {
        override val id = spec.id
        override val signals = flow {
            val urls = (spec.target as RelayTarget.Explicit).urls
            events.filter { event -> spec.filters.any { matchesBadgeFilter(event, it) } }
                .forEach { emit(SubscriptionSignal.Event(urls.first(), it, false)) }
            delay(wait)
            emit(SubscriptionSignal.FetchCompleted(urls.associateWith { if (complete) RelayOutcome.Eose else RelayOutcome.TimedOut }, !complete))
        }
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() = Unit
    }

    @Test fun badgeAppearsBeforeSlowSelectionAndMetadataEose() = runTest {
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { delayed(it, data, 9_000) }, validate = { true },
            validationDispatcher = StandardTestDispatcher(testScheduler)), { relays }, nowMillis = { testScheduler.currentTime })
        val fetch = backgroundScope.async { repo.refresh(setOf(badgeOwner)) }
        runCurrent()
        assertEquals("Badge", repo.observe(badgeOwner).value.badges.single().name)
        assertTrue(repo.observe(badgeOwner).value.loading)
        assertFalse(fetch.isCompleted)
        advanceTimeBy(60_001); runCurrent()
        assertTrue(fetch.await().complete)
        repo.close()
    }

    @Test fun completeReferencesAreReusedAndDefinitionsExpire() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { requests += it; badgeResponse(it, data) }, validate = { true },
            validationDispatcher = StandardTestDispatcher(testScheduler)), { relays }, nowMillis = { testScheduler.currentTime })
        repo.refresh(setOf(badgeOwner))
        val definitions = requests.count { it.filters.any { it.kinds == listOf(30009) } }
        val awards = requests.count { it.filters.any { it.kinds == listOf(8) } }
        repo.refresh(setOf(badgeOwner))
        assertEquals(definitions, requests.count { it.filters.any { it.kinds == listOf(30009) } })
        assertEquals(awards, requests.count { it.filters.any { it.kinds == listOf(8) } })
        advanceTimeBy(300_001)
        repo.refresh(setOf(badgeOwner))
        assertTrue(requests.count { it.filters.any { it.kinds == listOf(30009) } } > definitions)
        assertEquals(awards, requests.count { it.filters.any { it.kinds == listOf(8) } })
        repo.close()
    }

    @Test fun lateModernEmptySelectionRemovesEarlyLegacyBadges() = runTest {
        val legacy = badgeEvent(30008, tags = listOf(listOf("d", "profile_badges")) + badgePair().tags)
        val empty = badgeEvent(10008, time = 2, id = "0".repeat(64))
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { spec ->
            if (spec.filters.any { it.kinds == listOf(10008) }) object : SubscriptionSession {
                override val id = spec.id
                override val signals = flow {
                    emit(SubscriptionSignal.Event(relays.first(), legacy, false))
                    delay(200)
                    emit(SubscriptionSignal.Event(relays.first(), empty, false))
                    emit(SubscriptionSignal.FetchCompleted(relays.associateWith { RelayOutcome.Eose }, false))
                }
                override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
                override suspend fun close() = Unit
            } else badgeResponse(spec, data)
        }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        val fetch = backgroundScope.async { repo.refresh(setOf(badgeOwner)) }
        runCurrent()
        assertEquals(1, repo.observe(badgeOwner).value.badges.size)
        advanceTimeBy(201); runCurrent()
        assertTrue(fetch.await().complete)
        assertTrue(repo.observe(badgeOwner).value.badges.isEmpty())
        repo.close()
    }

    @Test fun anotherAuthorStartsWhileFirstAuthorWaitsAndMountedCardsDoNotRequeue() = runTest {
        val second = "2".repeat(64)
        val blocked = CompletableDeferred<Unit>()
        val authors = mutableListOf<List<String>>()
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { spec ->
            val selection = spec.filters.firstOrNull { it.kinds == listOf(10008) }
            if (selection != null) authors += selection.authors.orEmpty()
            if (selection?.authors == listOf(badgeOwner)) object : SubscriptionSession {
                override val id = spec.id
                override val signals = flow<SubscriptionSignal> {
                    blocked.await()
                    emit(SubscriptionSignal.FetchCompleted(relays.associateWith { RelayOutcome.Eose }, false))
                }
                override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
                override suspend fun close() = Unit
            } else badgeResponse(spec, emptyList())
        }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        val release = repo.acquire(badgeOwner)
        advanceTimeBy(101); runCurrent()
        val releaseAgain = repo.acquire(badgeOwner)
        val releaseSecond = repo.acquire(second)
        advanceTimeBy(101); runCurrent()
        assertTrue(listOf(second) in authors)
        assertEquals(1, authors.count { badgeOwner in it })
        blocked.complete(Unit); runCurrent()
        assertEquals(1, authors.count { badgeOwner in it })
        release(); releaseAgain(); releaseSecond(); repo.close()
    }

    @Test fun incompleteDeletionCheckDoesNotBecomeCompleteRefresh() = runTest {
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { spec ->
            badgeResponse(spec, data, complete = spec.filters.none { it.kinds == listOf(5) })
        }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        assertFalse(repo.refresh(setOf(badgeOwner), forceDependencies = true).complete)
        assertEquals(1, repo.observe(badgeOwner).value.badges.size)
        // Partial coverage is shown as partial, not as an error; relays did answer.
        assertTrue(repo.observe(badgeOwner).value.partial)
        assertNull(repo.observe(badgeOwner).value.error)
        repo.close()
    }

    @Test fun sharedReferenceRequestsOpenOnceAndRelayCoverageIsDistinct() = runTest {
        var opened = 0
        val transport = BadgeTransport(open = { opened++; delayed(it, data, 100) }, validate = { true },
            validationDispatcher = StandardTestDispatcher(testScheduler))
        val queries = BadgeFetchCoordinator(transport, now = { testScheduler.currentTime })
        val filter = listOf(NostrFilter(kinds = listOf(8), ids = listOf(badgeAward().id)))
        val first = async { queries.fetch(filter, relays, 300_000) }
        val second = async { queries.fetch(filter, relays, 300_000) }
        runCurrent()
        assertEquals(1, opened)
        advanceTimeBy(101); runCurrent()
        assertEquals(first.await(), second.await())
        queries.fetch(filter, relays, 300_000)
        assertEquals(1, opened)
        queries.fetch(filter, relays + "wss://two.example", 300_000)
        assertEquals(2, opened)
    }

    @Test fun incompleteResponsesAreRetriedAndCancelledOwnerDoesNotCancelWaiters() = runTest {
        var opened = 0
        var block = true
        val transport = BadgeTransport(open = { spec ->
            opened++
            if (block) object : SubscriptionSession {
                override val id = spec.id
                override val signals = flow<SubscriptionSignal> { awaitCancellation() }
                override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
                override suspend fun close() = Unit
            } else badgeResponse(spec, emptyList(), complete = false)
        }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler))
        val queries = BadgeFetchCoordinator(transport, now = { testScheduler.currentTime })
        val filter = listOf(NostrFilter(kinds = listOf(8)))
        val owner = backgroundScope.async { queries.fetch(filter, relays, 300_000) }
        val waiter = backgroundScope.async { queries.fetch(filter, relays, 300_000) }
        runCurrent(); block = false; owner.cancel(); runCurrent()
        // The waiter was not cancelled itself, so it re-runs the shared query on its own.
        assertTrue(owner.isCancelled)
        assertFalse(waiter.await().complete)
        assertFalse(queries.fetch(filter, relays, 300_000).complete)
        assertFalse(queries.fetch(filter, relays, 300_000).complete)
        assertEquals(4, opened)
    }

    @Test fun duplicateValidatedDeliveryIsNotValidatedOrNotifiedTwice() = runTest {
        var validated = 0
        val received = mutableListOf<NostrEvent>()
        val good = badgeAward()
        val transport = BadgeTransport(open = { badgeResponse(it, listOf(good.copy(content = "invalid"), good, good)) },
            validate = { validated++; it.content != "invalid" }, validationDispatcher = StandardTestDispatcher(testScheduler))
        transport.fetch(listOf(NostrFilter(kinds = listOf(8))), relays, received::add)
        assertEquals(2, validated)
        assertEquals(listOf(good), received)
    }
    @Test fun existingEventsPopulateNewObserverWithoutAnyRelayRequest() = runTest {
        var opened = 0
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { opened++; badgeResponse(it, data) },
            validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        repo.ingest(data)
        assertEquals("Badge", repo.observe(badgeOwner).value.badges.single().name)
        assertEquals(0, opened)
        repo.close()
    }

    @Test fun explicitReloadBypassesDefinitionAndDeletionTtl() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { requests += it; badgeResponse(it, data) },
            validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        val release = repo.acquire(badgeOwner)
        advanceTimeBy(101); runCurrent()
        val initialDefinitions = requests.count { it.filters.any { it.kinds == listOf(30009) } }
        val initialDeletion = requests.count { it.filters.any { it.kinds == listOf(5) } }
        val initialAwards = requests.count { it.filters.any { it.kinds == listOf(8) } }
        repo.request(badgeOwner, force = true)
        advanceTimeBy(101); runCurrent()
        assertTrue(requests.count { it.filters.any { it.kinds == listOf(30009) } } > initialDefinitions)
        assertTrue(requests.count { it.filters.any { it.kinds == listOf(5) } } > initialDeletion)
        assertEquals(initialAwards, requests.count { it.filters.any { it.kinds == listOf(8) } })
        release(); repo.close()
    }

    @Test fun lateIssuerRelaySuppliesNewerDefinitionAfterEarlyBadgeAppears() = runTest {
        val issuerMetadata = badgeEvent(10002, badgeIssuer, listOf(listOf("r", "wss://issuer.example", "write")), id = "7".repeat(64))
        val updated = badgeDefinition().copy(id = "8".repeat(64), createdAt = 3,
            tags = listOf(listOf("d", "award:one"), listOf("name", "Updated")))
        val requests = mutableListOf<SubscriptionSpec>()
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { spec ->
            requests += spec
            val target = (spec.target as RelayTarget.Explicit).urls
            val events = if ("wss://issuer.example" in target) data + updated else data + issuerMetadata
            delayed(spec, events, if (spec.filters.any { it.kinds == listOf(10002) }) 200 else 0)
        }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        val fetch = backgroundScope.async { repo.refresh(setOf(badgeOwner)) }
        runCurrent()
        assertEquals("Badge", repo.observe(badgeOwner).value.badges.single().name)
        advanceTimeBy(1_001); runCurrent()
        assertTrue(fetch.await().complete)
        assertEquals("Updated", repo.observe(badgeOwner).value.badges.single().name)
        assertTrue(requests.any { "wss://issuer.example" in (it.target as RelayTarget.Explicit).urls &&
            it.filters.any { it.kinds == listOf(30009) } })
        repo.close()
    }

    @Test fun lateDeletionRemovesEarlyBadgeAndCachedEventsCannotReviveIt() = runTest {
        val removed = badgeEvent(5, badgeIssuer, listOf(listOf("a", badgeAddress.value)), time = 3, id = "9".repeat(64))
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { spec ->
            if (spec.filters.any { it.kinds == listOf(5) }) object : SubscriptionSession {
                override val id = spec.id
                override val signals = flow {
                    delay(200)
                    if (spec.filters.any { matchesBadgeFilter(removed, it) }) emit(SubscriptionSignal.Event(relays.first(), removed, false))
                    val urls = (spec.target as RelayTarget.Explicit).urls
                    emit(SubscriptionSignal.FetchCompleted(urls.associateWith { RelayOutcome.Eose }, false))
                }
                override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
                override suspend fun close() = Unit
            } else badgeResponse(spec, data)
        }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        val fetch = backgroundScope.async { repo.refresh(setOf(badgeOwner)) }
        runCurrent()
        assertEquals(1, repo.observe(badgeOwner).value.badges.size)
        advanceTimeBy(1_001); runCurrent()
        assertTrue(fetch.await().complete)
        assertTrue(repo.observe(badgeOwner).value.badges.isEmpty())
        repo.refresh(setOf(badgeOwner))
        assertTrue(repo.observe(badgeOwner).value.badges.isEmpty())
        repo.close()
    }

    @Test fun emptyCompleteResponseExpiresAndForceIgnoresCache() = runTest {
        var time = 0L
        var opened = 0
        val queries = BadgeFetchCoordinator(BadgeTransport(open = { opened++; badgeResponse(it, emptyList()) }, validate = { true },
            validationDispatcher = StandardTestDispatcher(testScheduler)), now = { time })
        val filter = listOf(NostrFilter(kinds = listOf(30009), authors = listOf(badgeIssuer), dTags = listOf("absent")))
        queries.fetch(filter, relays, 300_000)
        queries.fetch(filter, relays, 300_000)
        assertEquals(1, opened)
        time = 30_001
        queries.fetch(filter, relays, 300_000)
        assertEquals(2, opened)
        queries.fetch(filter, relays, 300_000, force = true)
        assertEquals(3, opened)
    }

    @Test fun sharedDefinitionUpdatesEveryDependentAuthor() = runTest {
        val second = "2".repeat(64)
        val secondAward = badgeAward("3".repeat(64)).copy(tags = listOf(listOf("a", badgeAddress.value), listOf("p", second)))
        val secondProfile = badgeEvent(10008, second, tags = badgePair(secondAward.id).tags, id = "4".repeat(64))
        val repo = BadgeRepository(backgroundScope, BadgeTransport(validate = { true }), { relays })
        repo.observe(badgeOwner); repo.observe(second)
        repo.ingest(data + secondAward + secondProfile)
        assertEquals("Badge", repo.observe(badgeOwner).value.badges.single().name)
        assertEquals("Badge", repo.observe(second).value.badges.single().name)
        repo.ingest(listOf(badgeDefinition().copy(id = "5".repeat(64), createdAt = 2,
            tags = listOf(listOf("d", "award:one"), listOf("name", "Changed")))))
        assertEquals("Changed", repo.observe(badgeOwner).value.badges.single().name)
        assertEquals("Changed", repo.observe(second).value.badges.single().name)
        repo.close()
    }

    @Test fun setReferencesRenderBeforeSetEoseAndSetDeletionRemovesThem() = runTest {
        val set = badgeEvent(30008, tags = listOf(listOf("d", "collection")) + badgePair().tags, id = "f".repeat(64))
        val selection = badgeEvent(10008, tags = listOf(listOf("a", "30008:$badgeOwner:collection")))
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { delayed(it, listOf(selection, set, badgeAward(), badgeDefinition()), 9_000) },
            validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        val fetch = backgroundScope.async { repo.refresh(setOf(badgeOwner)) }
        runCurrent()
        assertEquals(1, repo.observe(badgeOwner).value.badges.size)
        assertFalse(fetch.isCompleted)
        repo.ingest(listOf(badgeEvent(5, tags = listOf(listOf("e", set.id)), id = "6".repeat(64))))
        assertTrue(repo.observe(badgeOwner).value.badges.isEmpty())
        fetch.cancel(); runCurrent(); repo.close()
    }

    @Test fun oneFinishedRefreshDoesNotClearAnotherRefreshLoadingState() = runTest {
        var selections = 0
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { spec ->
            val selection = spec.filters.any { it.kinds == listOf(10008) }
            delayed(spec, emptyList(), if (selection && ++selections == 1) 9_000 else 0)
        }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        val first = backgroundScope.async { repo.refresh(setOf(badgeOwner)) }
        runCurrent()
        val second = backgroundScope.async { repo.refresh(setOf(badgeOwner), forceDependencies = true) }
        runCurrent()
        assertTrue(second.isCompleted)
        assertTrue(second.await().complete)
        assertFalse(first.isCompleted)
        assertTrue(repo.observe(badgeOwner).value.loading)
        assertTrue(repo.observe(badgeOwner).value.partial)
        advanceTimeBy(9_001); runCurrent()
        assertTrue(first.await().complete)
        assertFalse(repo.observe(badgeOwner).value.loading)
        repo.close()
    }

}
