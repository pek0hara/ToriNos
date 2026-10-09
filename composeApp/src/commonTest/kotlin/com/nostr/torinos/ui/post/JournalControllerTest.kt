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
import com.nostr.torinos.journal.JournalInteractionSource
import com.nostr.torinos.journal.JournalMemberStorage
import com.nostr.torinos.journal.OTHER
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
    fun calendarIsHiddenByDefault() = runTest {
        val controller = JournalController(
            accountSession = session(),
            scope = backgroundScope,
            clock = utcClock(),
            eventSource = FakeRelay(),
            engagementSource = FakeEngagement(emptyList()),
            referenceFetcher = NoReferences,
            profileSource = NoProfiles,
            cachedReceivedLikes = { _, _, _ -> emptyList() },
            memberStorage = FakeMemberStorage(),
        )
        assertFalse(controller.state.value.showCalendar)
    }

    @Test
    fun returningToJournalDoesNotReplayCalendarToggle() = runTest {
        val controller = controller(FakeRelay())
        controller.applyCalendarToggleRequest(0)
        assertTrue(controller.state.value.showCalendar)

        controller.applyCalendarToggleRequest(1)
        assertFalse(controller.state.value.showCalendar)

        // 詳細から戻ると、同じ要求番号で画面の Effect が再実行される。
        controller.applyCalendarToggleRequest(1)
        assertFalse(controller.state.value.showCalendar)

        controller.applyCalendarToggleRequest(2)
        assertTrue(controller.state.value.showCalendar)
    }

    @Test
    fun returningToJournalPreservesCalendarHiddenWithHeaderButton() = runTest {
        val controller = controller(FakeRelay())
        controller.applyCalendarToggleRequest(1)
        controller.applyCalendarToggleRequest(2)
        controller.toggleCalendar()
        assertFalse(controller.state.value.showCalendar)

        controller.applyCalendarToggleRequest(2)
        assertFalse(controller.state.value.showCalendar)
    }

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
        assertTrue(state.coverage.hasLoadedMonth(sep1, state.kinds, sep15, setOf(OWNER)))
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
        assertFalse(controller.state.value.coverage.hasLoadedMonth(sep1, controller.state.value.kinds, sep15, setOf(OWNER)))
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

    @Test
    fun futureDatesCannotBeSelected() = runTest {
        val controller = controller(FakeRelay())
        controller.selectDate(LocalDate(2026, 9, 16))
        assertEquals(sep15, controller.state.value.selectedDate)
    }

    @Test
    fun goToTodayReturnsFromAnEarlierMonth() = runTest {
        val controller = controller(FakeRelay(post("today")))
        controller.setRelayUrl("wss://memo")
        settle()
        controller.previousMonth()
        settle()
        assertEquals(LocalDate(2026, 8, 1), controller.state.value.selectedMonth)

        controller.goToToday()
        settle()
        assertEquals(sep1, controller.state.value.selectedMonth)
        assertEquals(sep15, controller.state.value.selectedDate)
        assertEquals(listOf("today"), controller.state.value.visibleEntries.map { it.event.id })
    }

    @Test
    fun savedMembersAreLoadedBeforeTheFirstFetchButStartUnselected() = runTest {
        val friend = hexId(1)
        val relay = FakeRelay(post("mine"), post("theirs", pubkey = friend))
        val controller = controller(relay, storage = FakeMemberStorage(friend))

        controller.setRelayUrl("wss://memo")
        settle()

        assertEquals(listOf(OWNER, friend), controller.state.value.members)
        assertEquals(emptySet(), controller.state.value.selectedMembers)
        // 選んでいないメンバーは取得しない。
        assertTrue(relay.requests.all { it.members == setOf(OWNER) })
        assertEquals(listOf("mine"), controller.state.value.visibleEntries.map { it.event.id })

        // 選んだ人の分だけを取る。
        val before = relay.requests.size
        controller.toggleMemberSelection(OWNER)
        controller.toggleMemberSelection(friend)
        settle()
        assertTrue(relay.requests.drop(before).isNotEmpty())
        assertTrue(relay.requests.drop(before).all { it.members == setOf(friend) })
        assertEquals(setOf("mine", "theirs"), controller.state.value.visibleEntries.map { it.event.id }.toSet())

        // 外してまた選んでも、取得済みなので取り直さない。
        val loaded = relay.requests.size
        controller.toggleMemberSelection(friend)
        controller.toggleMemberSelection(friend)
        settle()
        assertEquals(loaded, relay.requests.size)
    }

    @Test
    fun addingAMemberRefetchesWithThemAndSavesTheList() = runTest {
        val friend = hexId(1)
        val storage = FakeMemberStorage()
        val relay = FakeRelay(post("mine"), post("theirs", pubkey = friend))
        val controller = controller(relay, storage = storage)
        controller.setRelayUrl("wss://memo")
        settle()
        assertEquals(listOf("mine"), controller.state.value.visibleEntries.map { it.event.id })

        val before = relay.requests.size
        controller.addMember(friend)
        settle()

        assertEquals(listOf(friend), storage.saved)
        assertEquals(setOf("mine", "theirs"), controller.state.value.visibleEntries.map { it.event.id }.toSet())
        // 追加した人の分だけを取り、自分の分は取り直さない。
        assertTrue(relay.requests.drop(before).all { it.members == setOf(friend) })

        // 外すときは取得しない。
        val afterAdd = relay.requests.size
        controller.removeMember(friend)
        settle()
        assertEquals(afterAdd, relay.requests.size)
        assertEquals(emptyList(), storage.saved)
        assertEquals(listOf("mine"), controller.state.value.visibleEntries.map { it.event.id })
    }

    @Test
    fun removingAMemberDuringTheirFetchLeavesNoTraceOfThem() = runTest {
        val friend = hexId(1)
        val relay = FakeRelay(post("mine"), post("theirs", pubkey = friend))
        val controller = controller(relay)
        controller.setRelayUrl("wss://memo")
        settle()

        val gate = CompletableDeferred<Unit>()
        relay.gate = gate
        controller.addMember(friend)
        settle()
        controller.removeMember(friend)
        gate.complete(Unit)
        settle()

        val state = controller.state.value
        assertEquals(listOf(OWNER), state.members)
        assertNull(state.timeline["theirs"])
        assertFalse(state.coverage.byMember.containsKey(friend))
        assertEquals(listOf("mine"), state.visibleEntries.map { it.event.id })
    }

    @Test
    fun selectingMembersOnlyFiltersTheDisplay() = runTest {
        val friend = hexId(1)
        val relay = FakeRelay(post("mine"), post("theirs", pubkey = friend))
        val controller = controller(relay, storage = FakeMemberStorage(friend))
        controller.setRelayUrl("wss://memo")
        settle()

        // 選んだ人だけを表示する。
        controller.toggleMemberSelection(friend)
        settle()
        assertEquals(listOf("theirs"), controller.state.value.visibleEntries.map { it.event.id })
        val requestCount = relay.requests.size

        controller.toggleMemberSelection(OWNER)
        assertEquals(setOf("mine", "theirs"), controller.state.value.visibleEntries.map { it.event.id }.toSet())

        // 誰も選ばなければ、既定の自分だけに戻る。取得済みなので取り直さない。
        controller.toggleMemberSelection(OWNER)
        controller.toggleMemberSelection(friend)
        assertEquals(listOf("mine"), controller.state.value.visibleEntries.map { it.event.id })
        assertEquals(requestCount, relay.requests.size)
    }

    @Test
    fun likeBetweenMembersIsOneEntryCountedForBothSides() = runTest {
        val friend = hexId(1)
        val like = reaction("like", targetId = "t", pubkey = OWNER, targetAuthor = friend)
        val controller = controller(FakeRelay(like), storage = FakeMemberStorage(friend))
        controller.setKinds(setOf(JournalActivityKind.Like, JournalActivityKind.ReceivedLike))
        controller.setRelayUrl("wss://memo")
        settle()
        controller.toggleMemberSelection(OWNER)
        controller.toggleMemberSelection(friend)
        settle()

        assertEquals(listOf("like"), controller.state.value.visibleEntries.map { it.event.id })
        assertEquals(1, controller.state.value.kindCounts[JournalActivityKind.Like])
        assertEquals(1, controller.state.value.kindCounts[JournalActivityKind.ReceivedLike])

        controller.toggleMemberSelection(OWNER)
        assertEquals(0, controller.state.value.kindCounts[JournalActivityKind.Like])
        assertEquals(1, controller.state.value.kindCounts[JournalActivityKind.ReceivedLike])
    }

    @Test
    fun membersAreLimited() = runTest {
        val controller = controller(FakeRelay())
        settle()
        (1..JOURNAL_MAX_ADDED_MEMBERS + 1).forEach { controller.addMember(hexId(it)) }
        settle()

        assertEquals(JOURNAL_MAX_ADDED_MEMBERS + 1, controller.state.value.members.size)
        assertFalse(controller.state.value.canAddMember)
    }

    @Test
    fun othersJournalStartsWithThatUserAndAddsMembersWithoutSaving() = runTest {
        val friend = hexId(2)
        val storage = FakeMemberStorage(hexId(1))
        val relay = FakeRelay(post("theirs", pubkey = OTHER), post("friend", pubkey = friend))
        val controller = JournalController(
            targetPubkey = OTHER,
            accountSession = session(),
            scope = backgroundScope,
            clock = utcClock(),
            eventSource = relay,
            engagementSource = FakeEngagement(emptyList()),
            referenceFetcher = NoReferences,
            profileSource = NoProfiles,
            cachedReceivedLikes = { _, _, _ -> emptyList() },
            memberStorage = storage,
            initialShowCalendar = true,
        )
        controller.setRelayUrl("wss://memo")
        settle()
        assertEquals(listOf(OTHER), controller.state.value.members)
        assertEquals(listOf(OTHER), relay.requests.first().filters.first().authors)

        val before = relay.requests.size
        controller.addMember(friend)
        settle()
        assertEquals(listOf(OTHER, friend), controller.state.value.members)
        assertTrue(relay.requests.drop(before).all { it.members == setOf(friend) })
        assertEquals(setOf("theirs", "friend"), controller.state.value.visibleEntries.map { it.event.id }.toSet())
        assertEquals(listOf(hexId(1)), storage.saved)

        // 開いた人は外せない。
        controller.removeMember(OTHER)
        assertEquals(listOf(OTHER, friend), controller.state.value.members)
    }

    @Test
    fun membersCannotChangeBeforeSavedMembersAreLoaded() = runTest {
        val controller = controller(FakeRelay(), storage = FakeMemberStorage(hexId(1)))
        // 読み込み（init で起動）の前に追加しても、読み込み結果で上書きされるため受け付けない。
        controller.addMember(hexId(2))
        settle()
        assertEquals(listOf(OWNER, hexId(1)), controller.state.value.members)
    }

    @Test
    fun candidatesAreRankedByInteractions() = runTest {
        val alice = hexId(1)
        val bob = hexId(2)
        val interactions = listOf(
            reaction("l1", targetId = "t1", pubkey = OWNER, targetAuthor = bob),
            reaction("l2", targetId = "t2", pubkey = alice, targetAuthor = OWNER),
            reaction("l3", targetId = "t3", pubkey = alice, targetAuthor = OWNER),
        )
        var fetches = 0
        val controller = controller(FakeRelay(), interactions = { _, _ -> fetches += 1; interactions })
        controller.loadMemberCandidates()
        settle()

        assertEquals(
            listOf(JournalMemberCandidate(alice, 2), JournalMemberCandidate(bob, 1)),
            controller.state.value.memberCandidates.candidates,
        )
        assertFalse(controller.state.value.memberCandidates.isLoading)

        // しばらくはシートを開き直しても取り直さない。
        controller.loadMemberCandidates()
        settle()
        assertEquals(1, fetches)
    }

    /** `advanceUntilIdle`は前景の処理がなくなると止まり`backgroundScope`を進めないため、現在時刻の処理をすべて実行する。 */
    private fun TestScope.settle() = testScheduler.runCurrent()

    private fun TestScope.controller(
        relay: FakeRelay,
        engagement: FakeEngagement = FakeEngagement(emptyList()),
        clock: JournalClock = utcClock(),
        storage: JournalMemberStorage = FakeMemberStorage(),
        interactions: JournalInteractionSource = JournalInteractionSource { _, _ -> emptyList() },
    ) = JournalController(
        accountSession = session(),
        scope = backgroundScope,
        clock = clock,
        eventSource = relay,
        engagementSource = engagement,
        referenceFetcher = NoReferences,
        profileSource = NoProfiles,
        cachedReceivedLikes = { _, _, _ -> emptyList() },
        memberStorage = storage,
        interactionSource = interactions,
        // 既存のテストは日ごとの表示で確かめる。
        initialShowCalendar = true,
    )

    private class FakeMemberStorage(vararg initial: String) : JournalMemberStorage {
        var saved: List<String> = initial.toList()

        override suspend fun load(ownPubkey: String): List<String> = saved
        override suspend fun save(ownPubkey: String, members: List<String>) {
            saved = members
        }
    }

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
