package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

internal fun badgeResponse(spec: SubscriptionSpec, events: List<NostrEvent>, complete: Boolean = true, onClose: () -> Unit = {}): SubscriptionSession = object : SubscriptionSession {
    override val id = spec.id
    override val signals = flow {
        events.filter { event -> spec.filters.any { matchesBadgeFilter(event, it) } }.forEach { emit(SubscriptionSignal.Event("wss://one.example", it, false)) }
        val urls = (spec.target as RelayTarget.Explicit).urls
        emit(SubscriptionSignal.FetchCompleted(urls.associateWith { if (complete) RelayOutcome.Eose else RelayOutcome.TimedOut }, !complete))
    }
    override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
    override suspend fun close() { onClose() }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BadgeRepositoryTest {
    private val relays = setOf("wss://one.example")
    @Test fun validatesBeforeDeduplicationAndClosesFiniteFetch() = runTest {
        val good = badgeAward()
        var closed = false
        var requested: SubscriptionSpec? = null
        val transport = BadgeTransport(open = { spec -> requested = spec; badgeResponse(spec, listOf(good.copy(content = "invalid"), good), onClose = { closed = true }) }, validate = { it.content != "invalid" }, validationDispatcher = StandardTestDispatcher(testScheduler))
        val result = transport.fetch(listOf(NostrFilter(kinds = listOf(8))), relays)
        assertEquals(listOf(good), result.events)
        assertTrue(result.complete)
        assertTrue(closed)
        assertFalse(requested!!.deduplicateEvents)
        assertTrue(requested!!.id.length <= 64)
    }
    @Test fun timeoutIsDifferentFromAbsenceAndNoRelayIsNotSuccess() = runTest {
        val transport = BadgeTransport(open = { spec -> badgeResponse(spec, emptyList(), complete = false) }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler))
        assertFalse(transport.fetch(listOf(NostrFilter(kinds = listOf(8))), relays).complete)
        assertFalse(transport.fetch(listOf(NostrFilter(kinds = listOf(8))), emptySet()).complete)
    }
    @Test fun forgedIssuerNeverAppearsAndMissingImagesAreAllowed() = runTest {
        var data = listOf(badgeEvent(10008, tags = badgePair().tags), badgeAward().copy(pubkey = badgeOwner), badgeDefinition())
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { badgeResponse(it, data) }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        repo.refresh(setOf(badgeOwner))
        assertTrue(repo.observe(badgeOwner).value.badges.isEmpty())
        assertEquals(BadgeReferenceStatus.Invalid, repo.observe(badgeOwner).value.references.values.single())
        data = listOf(badgeEvent(10008, tags = badgePair().tags), badgeAward(), badgeDefinition())
        repo.refresh(setOf(badgeOwner))
        assertEquals("Badge", repo.observe(badgeOwner).value.badges.single().name)
        repo.close()
    }
    @Test fun expandsOwnSetInPlaceAndDeduplicatesDefinitions() = runTest {
        val set = badgeEvent(30008, tags = listOf(listOf("d", "collection")) + badgePair().tags, id = "f".repeat(64))
        val profile = badgeEvent(10008, tags = listOf(listOf("a", "30008:$badgeOwner:collection")) + badgePair().tags)
        val data = listOf(profile, set, badgeAward(), badgeDefinition())
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { badgeResponse(it, data) }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        repo.refresh(setOf(badgeOwner))
        assertEquals(1, repo.observe(badgeOwner).value.badges.size)
        val deletion = badgeEvent(5, tags = listOf(listOf("e", set.id)), id = "1".repeat(64))
        repo.ingest(listOf(deletion))
        assertEquals(1, repo.expand(profile).size) // direct pair remains; deleted set is not expanded
        repo.close()
    }
    @Test fun definitionDeletionCannotBeUndoneByRefetchingOldEvent() = runTest {
        val data = listOf(badgeEvent(10008, tags = badgePair().tags), badgeAward(), badgeDefinition())
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { badgeResponse(it, data) }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        repo.refresh(setOf(badgeOwner))
        assertEquals(1, repo.observe(badgeOwner).value.badges.size)
        repo.ingest(listOf(badgeEvent(5, badgeIssuer, listOf(listOf("a", badgeAddress.value)), time = 2, id = "1".repeat(64))))
        repo.refresh(setOf(badgeOwner))
        assertTrue(repo.observe(badgeOwner).value.badges.isEmpty())
        repo.ingest(listOf(badgeDefinition().copy(createdAt = 3, id = "2".repeat(64))))
        assertEquals(1, repo.observe(badgeOwner).value.badges.size)
        repo.close()
    }
    @Test fun subscriptionsCloseOnCallerCancellation() = runTest {
        var closed = false
        val transport = BadgeTransport(open = { spec -> object : SubscriptionSession {
            override val id = spec.id
            override val signals = flow<SubscriptionSignal> { awaitCancellation() }
            override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
            override suspend fun close() { closed = true }
        } }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler))
        val job = backgroundScope.launch { transport.fetch(listOf(NostrFilter(kinds = listOf(8))), relays) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(closed)
    }
    @Test fun multipleCardsShareOneAuthorFetchAndInitialDemandIsNotLost() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { spec -> requests += spec; badgeResponse(spec, emptyList()) },
            validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        val releaseA = repo.acquire(badgeOwner)
        val releaseB = repo.acquire(badgeOwner)
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(1, requests.count { it.filters.any { filter -> filter.kinds == listOf(10008) } })
        releaseA(); releaseA(); releaseB()
        repo.close()
    }
    @Test fun nip65WriteRelaysAndAwardHintsAreAddedAsBoundedCandidates() = runTest {
        val requests = mutableListOf<SubscriptionSpec>()
        val metadata = badgeEvent(10002, badgeOwner, listOf(listOf("r", "wss://author.example", "write"), listOf("r", "wss://mentions.example", "read")), id = "8".repeat(64))
        val data = listOf(metadata, badgeEvent(10008, tags = badgePair().tags), badgeAward(), badgeDefinition())
        val repo = BadgeRepository(backgroundScope, BadgeTransport(open = { spec -> requests += spec; badgeResponse(spec, data) },
            validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays })
        repo.refresh(setOf(badgeOwner))
        assertTrue(requests.any { spec -> spec.filters.any { it.kinds == listOf(10008) } &&
            "wss://author.example" in (spec.target as RelayTarget.Explicit).urls })
        assertTrue(requests.any { "wss://hint.example" in (it.target as RelayTarget.Explicit).urls })
        assertTrue("wss://mentions.example" in repo.relaysFor(setOf(badgeOwner), readMentions = true))
        repo.close()
    }
}
