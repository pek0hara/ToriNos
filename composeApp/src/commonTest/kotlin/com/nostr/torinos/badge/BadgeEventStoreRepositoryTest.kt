package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BadgeEventStoreRepositoryTest {
    private val relays = setOf("wss://one.example")
    private val data = listOf(badgeEvent(10008, tags = badgePair().tags), badgeAward(), badgeDefinition())

    @Test fun newRepositoryShowsBadgesReceivedByEarlierAccountBeforeAnyRequest() = runTest {
        val store = BadgeEventStore()
        val first = repository(store) { data }
        first.refresh(setOf(badgeOwner))
        first.close()

        var opened = 0
        val second = repository(store) { opened++; emptyList() }
        val state = second.observe(badgeOwner)
        runCurrent()

        assertEquals("Badge", state.value.badges.single().name)
        assertEquals(0, opened)
        second.close()
    }

    @Test fun storedAuthorIsStillFetched() = runTest {
        val store = BadgeEventStore().apply { put(data) }
        val requests = mutableListOf<SubscriptionSpec>()
        val repo = repository(store, requests) { data }

        val release = repo.acquire(badgeOwner)
        advanceTimeBy(101); runCurrent()

        assertTrue(requests.any { spec -> spec.filters.any { it.kinds == listOf(10008) } })
        release(); repo.close()
    }

    @Test fun deletionReceivedAfterRestoreHidesBadge() = runTest {
        val store = BadgeEventStore().apply { put(data) }
        val deletion = badgeEvent(5, badgeIssuer, listOf(listOf("e", badgeAward().id)), time = 3, id = "9".repeat(64))
        val repo = repository(store) { data + deletion }
        repo.observe(badgeOwner)
        runCurrent()
        assertEquals(1, repo.observe(badgeOwner).value.badges.size)

        repo.refresh(setOf(badgeOwner))

        assertTrue(repo.observe(badgeOwner).value.badges.isEmpty())
        assertTrue(deletion in store.eventsFor(badgeOwner))
        repo.close()
    }

    @Test fun observedAuthorIsSeededWhenStoreFinishesLoading() = runTest {
        val stored = BadgeEventStore().apply { put(data) }.encode(maxUsers = 10)
        val store = BadgeEventStore(ready = false)
        val repo = repository(store) { emptyList() }
        assertTrue(repo.observe(badgeOwner).value.badges.isEmpty())

        store.restore(stored)
        store.markReady()
        runCurrent()

        assertEquals("Badge", repo.observe(badgeOwner).value.badges.single().name)
        repo.close()
    }

    @Test fun acquiredAuthorIsSeededWhenStoreFinishesLoading() = runTest {
        val stored = BadgeEventStore().apply { put(data) }.encode(maxUsers = 10)
        val store = BadgeEventStore(ready = false)
        val repo = repository(store) { emptyList() }
        val release = repo.acquire(badgeOwner)

        store.restore(stored)
        store.markReady()
        runCurrent()

        assertEquals("Badge", repo.observe(badgeOwner).value.badges.single().name)
        release(); repo.close()
    }

    @Test fun unsharedEventsStayInTheirRepository() = runTest {
        val store = BadgeEventStore()
        val repo = repository(store) { emptyList() }

        repo.ingest(data, share = false)

        assertEquals("Badge", repo.observe(badgeOwner).value.badges.single().name)
        assertTrue(store.eventsFor(badgeOwner).isEmpty())
        repo.close()
    }

    @Test fun observingTouchesUserInStore() = runTest {
        var now = 0L
        val store = BadgeEventStore(maxUsers = 2, nowMillis = { now })
        val other = "1".repeat(64)
        store.put(data)
        now = 5; store.put(listOf(badgeEvent(10008, pubkey = other, id = "1".repeat(64))))
        val repo = repository(store) { emptyList() }

        now = 10; repo.observe(badgeOwner)
        now = 20; store.put(listOf(badgeEvent(10008, pubkey = "2".repeat(64), id = "2".repeat(64))))

        assertEquals(3, store.eventsFor(badgeOwner).size)
        assertTrue(store.eventsFor(other).isEmpty())
        repo.close()
    }

    private fun TestScope.repository(
        store: BadgeEventStore,
        requests: MutableList<SubscriptionSpec> = mutableListOf(),
        events: () -> List<NostrEvent>,
    ) = BadgeRepository(
        backgroundScope,
        BadgeTransport(
            open = { spec -> requests += spec; badgeResponse(spec, events()) },
            validate = { true },
            validationDispatcher = StandardTestDispatcher(testScheduler),
        ),
        { relays },
        eventStore = store,
    )
}
