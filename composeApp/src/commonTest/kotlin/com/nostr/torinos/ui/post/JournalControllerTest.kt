package com.nostr.torinos.ui.post

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.AccountSessionResources
import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.journal.JournalActivityKind
import com.nostr.torinos.journal.JournalClock
import com.nostr.torinos.journal.JournalEngagementSource
import com.nostr.torinos.journal.JournalEventSource
import com.nostr.torinos.journal.JournalFetchRequest
import com.nostr.torinos.journal.JournalFetchResult
import com.nostr.torinos.journal.OWNER
import com.nostr.torinos.journal.epochOf
import com.nostr.torinos.journal.hexId
import com.nostr.torinos.journal.post
import com.nostr.torinos.journal.reaction
import com.nostr.torinos.journal.utcClock
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.EventByIdResult
import com.nostr.torinos.network.TargetEventFetcher
import com.nostr.torinos.network.TargetLoadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalControllerTest {
    private val sep15 = LocalDate(2026, 9, 15)
    private val sep3 = LocalDate(2026, 9, 3)
    private val sep1 = LocalDate(2026, 9, 1)

    @Test
    fun opensTheSelectedDateFirstThenBackfillsTheMonth() = runTest {
        val relay = FakeRelay(post("today"), post("earlier", createdAt = epochOf(sep3)))
        val controller = controller(relay)

        controller.setRelayUrl("wss://memo")
        settle()

        assertEquals(listOf(sep15), relay.requests.first().dates)
        val state = controller.state.value
        assertEquals(listOf("today"), state.visibleEntries.map { it.event.id })
        assertEquals(mapOf(sep3 to 1, sep15 to 1), state.entryCountsByDate)
        assertTrue(state.coverage.hasLoadedMonth(sep1, state.kinds, sep15))
        assertFalse(state.isLoading)
    }

    @Test
    fun nothingIsFetchedBeforeTheRelayIsKnown() = runTest {
        val relay = FakeRelay(post("today"))
        val controller = controller(relay)

        controller.setKinds(setOf(JournalActivityKind.Repost))
        settle()

        assertTrue(relay.requests.isEmpty())
        assertEquals(setOf(JournalActivityKind.Repost), controller.state.value.kinds)
    }

    @Test
    fun loadedMonthsAreNotFetchedAgain() = runTest {
        val relay = FakeRelay(post("today"))
        val controller = controller(relay)
        controller.setRelayUrl("wss://memo")
        settle()
        val requestCount = relay.requests.size

        controller.previousMonth()
        settle()
        controller.nextMonth()
        settle()

        val augustRequests = relay.requests.drop(requestCount)
        assertTrue(augustRequests.isNotEmpty())
        assertTrue(augustRequests.all { request -> request.dates.all { it.month.ordinal == 7 } })
        assertEquals(sep15, controller.state.value.selectedDate)
    }

    @Test
    fun dateRefreshRemovesDeletedPostsOnlyWhenEveryRelayFinished() = runTest {
        val deleted = post("deleted")
        val relay = FakeRelay(deleted, post("kept"))
        val controller = controller(relay)
        controller.setRelayUrl("wss://memo")
        settle()
        relay.events -= deleted

        relay.complete = false
        controller.refresh()
        settle()
        assertEquals(setOf("deleted", "kept"), controller.state.value.visibleEntries.map { it.event.id }.toSet())

        relay.complete = true
        controller.refresh()
        settle()
        assertEquals(listOf("kept"), controller.state.value.visibleEntries.map { it.event.id })
    }

    @Test
    fun monthRefreshKeepsOldEntriesUntilTheDayIsFetched() = runTest {
        val relay = FakeRelay(post("earlier", createdAt = epochOf(sep3)))
        val controller = controller(relay)
        controller.setRelayUrl("wss://memo")
        settle()
        controller.toggleCalendar()

        relay.complete = false
        controller.refresh()
        settle()

        assertEquals(listOf("earlier"), controller.state.value.visibleEntries.map { it.event.id })
        assertFalse(controller.state.value.coverage.hasLoadedMonth(sep1, controller.state.value.kinds, sep15))
    }

    @Test
    fun switchingRelaysDropsEverythingFetchedFromThePreviousRelay() = runTest {
        val note = post(hexId(1))
        val relay = FakeRelay(note)
        val engagement = FakeEngagement(listOf(reaction("like", targetId = note.id)))
        val controller = controller(relay, engagement)
        controller.setRelayUrl("wss://a")
        settle()
        controller.setVisibleNoteIds(setOf(note.id))
        settle()
        assertEquals(1, controller.state.value.engagementOf(note.id).summary.reactionCount)
        assertEquals(1, controller.state.value.engagementOf(note.id).reactionEvents.size)

        relay.events.clear()
        controller.setRelayUrl("wss://b")
        settle()

        val state = controller.state.value
        assertTrue(state.timeline.isEmpty())
        assertTrue(state.engagement.isEmpty())
        assertTrue(state.referencedEvents.isEmpty())
    }

    @Test
    fun engagementIsFetchedOnceForVisibleNotes() = runTest {
        val note = post(hexId(1))
        val engagement = FakeEngagement(listOf(reaction("like", targetId = note.id)))
        val controller = controller(FakeRelay(note), engagement)
        controller.setRelayUrl("wss://memo")
        settle()

        controller.setVisibleNoteIds(setOf(note.id, "not-in-journal"))
        settle()
        controller.setVisibleNoteIds(emptySet())
        controller.setVisibleNoteIds(setOf(note.id))
        settle()

        assertEquals(listOf(setOf(note.id)), engagement.requests)
        assertEquals(1, controller.state.value.engagementOf(note.id).summary.likeReactionCount)
    }

    @Test
    fun resultsForAMonthThatWasLeftAreDropped() = runTest {
        val relay = FakeRelay(post("september"))
        val gate = CompletableDeferred<Unit>()
        relay.gate = gate
        val controller = controller(relay)
        controller.setRelayUrl("wss://memo")
        settle()

        relay.gate = null
        controller.previousMonth()
        gate.complete(Unit)
        settle()

        val state = controller.state.value
        assertEquals(LocalDate(2026, 8, 1), state.selectedMonth)
        assertNull(state.timeline["september"])
    }

    @Test
    fun receivedLikesComeFromAllRelaysForTheWholeMonth() = runTest {
        val relay = FakeRelay(reaction("got", targetId = "t", createdAt = epochOf(sep3)))
        val controller = controller(relay)
        controller.setKinds(setOf(JournalActivityKind.ReceivedLike))
        controller.setRelayUrl("wss://memo")
        settle()

        val receivedRequests = relay.requests.filter { JournalActivityKind.ReceivedLike in it.kinds }
        assertEquals(1, receivedRequests.size)
        assertEquals(15, receivedRequests.single().dates.size)
        assertEquals(mapOf(sep3 to 1), controller.state.value.entryCountsByDate)
    }

    @Test
    fun ownJournalWithoutASignerShowsAnError() = runTest {
        val controller = JournalController(
            scope = backgroundScope,
            clock = utcClock(),
            eventSource = FakeRelay(),
            engagementSource = FakeEngagement(emptyList()),
            referenceFetcher = NoReferences,
            profileSource = NoProfiles,
            cachedReceivedLikes = { _, _, _ -> emptyList() },
        )

        controller.setRelayUrl("wss://memo")
        settle()

        assertEquals("秘密鍵が設定されていません", controller.state.value.error)
    }

    @Test
    fun navigatingDatesSkipsDaysWithoutSelectedKinds() = runTest {
        val relay = FakeRelay(post("today"), post("earlier", createdAt = epochOf(sep3)))
        val controller = controller(relay)
        controller.setRelayUrl("wss://memo")
        settle()

        controller.previousDate()
        settle()
        assertEquals(sep3, controller.state.value.selectedDate)
        assertEquals(listOf("earlier"), controller.state.value.visibleEntries.map { it.event.id })

        controller.nextDate()
        settle()
        assertEquals(sep15, controller.state.value.selectedDate)
    }

    /** `advanceUntilIdle`は前景の処理がなくなると止まり`backgroundScope`を進めないため、現在時刻の処理をすべて実行する。 */
    private fun TestScope.settle() = testScheduler.runCurrent()

    private fun TestScope.controller(
        relay: FakeRelay,
        engagement: FakeEngagement = FakeEngagement(emptyList()),
        clock: JournalClock = utcClock(),
    ) = JournalController(
        accountSession = session(),
        scope = backgroundScope,
        clock = clock,
        eventSource = relay,
        engagementSource = engagement,
        referenceFetcher = NoReferences,
        profileSource = NoProfiles,
        cachedReceivedLikes = { _, _, _ -> emptyList() },
    )

    private fun session() = AccountSession(
        sessionId = "test",
        pubkey = OWNER,
        signer = OwnerSigner,
        resources = AccountSessionResources(),
    )

    /** フィルターに合うイベントを返すリレー。[complete]がfalseならタイムアウト扱い。 */
    private inner class FakeRelay(vararg initial: NostrEvent) : JournalEventSource {
        val events = initial.toMutableList()
        val requests = mutableListOf<JournalFetchRequest>()
        var complete = true
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun fetch(request: JournalFetchRequest): JournalFetchResult {
            requests += request
            gate?.await()
            val clock = utcClock()
            val matched = events.filter { event ->
                clock.dateOf(event.createdAt) in request.dates && request.filters.any { it.matches(event) }
            }
            return JournalFetchResult(request, matched, complete)
        }

        private fun NostrFilter.matches(event: NostrEvent): Boolean =
            (kinds == null || event.kind in kinds.orEmpty()) &&
                (authors == null || event.pubkey in authors.orEmpty()) &&
                (pTags == null || event.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) in pTags.orEmpty() })
    }

    private class FakeEngagement(private val events: List<NostrEvent>) : JournalEngagementSource {
        val requests = mutableListOf<Set<String>>()

        override suspend fun fetch(noteIds: Set<String>, relayUrl: String?, onEvent: (NostrEvent) -> Unit): Boolean {
            requests += noteIds
            events.forEach(onEvent)
            return true
        }
    }

    private object NoReferences : TargetEventFetcher {
        override suspend fun fetch(ids: Set<String>, onEvent: (NostrEvent) -> Unit) =
            EventByIdResult(TargetLoadState.NotFoundInQueriedRelays)
    }

    private object NoProfiles : JournalProfileSource {
        override fun cached(pubkeys: Set<String>): Map<String, NostrProfile> = emptyMap()
        override fun observe(pubkeys: Set<String>): Flow<Map<String, NostrProfile>> = flowOf(emptyMap())
        override suspend fun ensure(pubkeys: Set<String>, relayHint: String?) = Unit
    }

    private object OwnerSigner : AccountSigner {
        override val pubkey: String = OWNER
        override fun encryptToSelf(plaintext: String): String = plaintext
        override fun decrypt(content: String, peerPubkey: String): String = content
        override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?) =
            NostrEvent(id = "signed", pubkey = OWNER, createdAt = 0, kind = kind, tags = tags, content = content, sig = "sig")
    }
}
