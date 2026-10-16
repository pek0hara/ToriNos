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
import com.nostr.torinos.journal.JournalFetchStatus
import com.nostr.torinos.journal.JournalInteractionSource
import com.nostr.torinos.journal.JournalMemberStorage
import com.nostr.torinos.journal.JournalReferenceSource
import com.nostr.torinos.journal.OTHER
import com.nostr.torinos.journal.OWNER
import com.nostr.torinos.journal.daysOfMonthUntil
import com.nostr.torinos.journal.epochOf
import com.nostr.torinos.journal.hexId
import com.nostr.torinos.journal.post
import com.nostr.torinos.journal.reaction
import com.nostr.torinos.journal.reply
import com.nostr.torinos.journal.utcClock
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val DOWN = "wss://down"
    private val sep15 = LocalDate(2026, 9, 15)
    private val sep3 = LocalDate(2026, 9, 3)
    private val sep1 = LocalDate(2026, 9, 1)
    private val sep2 = LocalDate(2026, 9, 2)

    @Test
    fun calendarIsHiddenByDefault() = runTest {
        val controller = JournalController(
            accountSession = session(),
            scope = backgroundScope,
            clock = utcClock(),
            eventSource = FakeRelay(),
            engagementSource = FakeEngagement(emptyList()),
            referenceSource = NoReferences,
            profileSource = NoProfiles,
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
    fun journalStartsAsLoadingBeforeTheFirstFetch() = runTest {
        // 開く前（タブの切り替え中）に「投稿はありません・0件」を出さない。
        val controller = controller(FakeRelay(), showCalendar = false)
        assertTrue(controller.state.value.isLoading)
        // リレーが決まる前に読み込みが呼ばれても、読み込み中のまま待つ。
        controller.setKinds(setOf(JournalActivityKind.Repost))
        settle()
        assertTrue(controller.state.value.isLoading)
    }

    @Test
    fun monthListIsRevealedOnceTheMonthReferencesAndNamesAreReady() = runTest {
        val relay = FakeRelay(post("earlier", createdAt = epochOf(sep3)))
        relay.backfillGate = CompletableDeferred()
        val controller = controller(relay, showCalendar = false, profiles = KnownProfiles(OWNER))

        controller.setRelayUrl("wss://memo")
        settle()
        // 今日の分（0件）を取り終えても、月の残りの取得中は「取得中」のまま。
        assertFalse(controller.state.value.isLoading)
        assertTrue(controller.state.value.isBackfilling)

        relay.backfillGate?.complete(Unit)
        settle()
        assertFalse(controller.state.value.isBackfilling)
        assertEquals(listOf("earlier"), controller.state.value.visibleEntries.map { it.event.id })
        assertEquals("name-$OWNER", controller.state.value.profiles[OWNER]?.name)
    }

    @Test
    fun missingNamesDoNotHoldTheMonthListForever() = runTest {
        val relay = FakeRelay(post("earlier", createdAt = epochOf(sep3)))
        val controller = controller(relay, showCalendar = false, profiles = SilentProfiles)

        controller.setRelayUrl("wss://memo")
        settle()
        assertTrue(controller.state.value.isBackfilling)

        testScheduler.advanceTimeBy(1_001)
        settle()
        assertFalse(controller.state.value.isBackfilling)
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
    fun switchingRelaysDropsWhatWasFetchedFromThePreviousRelay() = runTest {
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
    }

    @Test
    fun switchingRelaysRefetchesReceivedLikesFromTheNewRelay() = runTest {
        val relay = FakeRelay(post("note", createdAt = epochOf(sep3)), reaction("got-on-a", targetId = "t", createdAt = epochOf(sep3)))
        val controller = controller(relay)
        controller.setKinds(setOf(JournalActivityKind.Post, JournalActivityKind.ReceivedLike))
        controller.setRelayUrl("wss://a")
        settle()
        assertEquals(setOf("note", "got-on-a"), controller.state.value.timeline.eventIdsOn(listOf(sep3)))

        // 新しいリレーには、別のもらったいいねだけがある。
        relay.events.clear()
        relay.events += reaction("got-on-b", targetId = "t", createdAt = epochOf(sep2))
        val before = relay.requests.size
        controller.setRelayUrl("wss://b")
        settle()

        val received = relay.requests.drop(before).filter { JournalActivityKind.ReceivedLike in it.kinds }
        assertTrue(received.isNotEmpty())
        assertTrue(received.all { it.target == RelayTarget.Single("wss://b") })
        assertEquals(setOf("got-on-b"), controller.state.value.timeline.eventIdsOn(sep1.daysOfMonthUntil(sep15)))
    }

    @Test
    fun receivedLikesAreLoadedWhenOneRelayIsDownAndOnlyThatRelayIsRetried() = runTest {
        val relay = FakeRelay(reaction("got", targetId = "t", createdAt = epochOf(sep3)))
        relay.downRelay = DOWN
        relay.downRelayEvents += reaction("late", targetId = "t", createdAt = epochOf(sep2))
        val controller = controller(relay)
        controller.setKinds(setOf(JournalActivityKind.ReceivedLike))
        // 全リレーを対象にした取得（リレー未選択）で確かめる。
        controller.setRelayUrl(null)
        settle()

        val state = controller.state.value
        assertTrue(state.coverage.hasLoadedMonth(sep1, state.kinds, sep15, setOf(OWNER)))
        assertEquals(setOf("got"), state.timeline.eventIdsOn(sep1.daysOfMonthUntil(sep15)))

        relay.downRelayRecovered = true
        testScheduler.advanceTimeBy(2_001)
        settle()

        val retried = relay.requests.filter { it.target == RelayTarget.Single(DOWN) }
        assertEquals(1, retried.size)
        assertEquals(setOf(JournalActivityKind.ReceivedLike), retried.single().kinds)
        assertEquals(setOf("got", "late"), controller.state.value.timeline.eventIdsOn(sep1.daysOfMonthUntil(sep15)))
    }

    @Test
    fun aRelayThatStaysDownIsGivenUpAfterThreeFailures() = runTest {
        val relay = FakeRelay(reaction("got", targetId = "t", createdAt = epochOf(sep3)))
        relay.downRelay = DOWN
        val controller = controller(relay)
        controller.setKinds(setOf(JournalActivityKind.ReceivedLike))
        // 全リレーを対象にした取得（リレー未選択）で確かめる。
        controller.setRelayUrl(null)
        settle()

        testScheduler.advanceTimeBy(60_000)
        settle()

        assertEquals(2, relay.requests.count { it.target == RelayTarget.Single(DOWN) })
    }

    @Test
    fun aRelayThatRefusesIsNotRetried() = runTest {
        val relay = FakeRelay(reaction("got", targetId = "t", createdAt = epochOf(sep3)))
        relay.downRelay = DOWN
        relay.downRelayRefuses = true
        val controller = controller(relay)
        controller.setKinds(setOf(JournalActivityKind.ReceivedLike))
        // 全リレーを対象にした取得（リレー未選択）で確かめる。
        controller.setRelayUrl(null)
        settle()

        testScheduler.advanceTimeBy(60_000)
        settle()

        assertTrue(relay.requests.none { it.target == RelayTarget.Single(DOWN) })
        val state = controller.state.value
        assertTrue(state.coverage.hasLoadedMonth(sep1, state.kinds, sep15, setOf(OWNER)))
    }

    @Test
    fun engagementRetriesOnlyAddAndNeverReplace() = runTest {
        // 取り直しの集計には、最初の取得の後に表示へ入った分（確定した自分のいいね等）が含まれない。
        // 置き換えるとそれが消えるため、取り直しは足すだけにする。
        val note = post(hexId(1))
        val earlier = reaction("earlier", targetId = note.id, pubkey = "earlier-reactor")
        val engagement = FakeEngagement(listOf(earlier))
        val controller = controller(FakeRelay(note), engagement)
        controller.setRelayUrl("wss://memo")
        settle()
        controller.setVisibleNoteIds(setOf(note.id))
        settle()

        engagement.events = listOf(reaction("now", targetId = note.id))
        engagement.downRelay = DOWN
        engagement.downRelayEvents += reaction("late", targetId = note.id, pubkey = "late-reactor")
        controller.refresh()
        settle()
        controller.setVisibleNoteIds(setOf(note.id))
        settle()
        engagement.downRelayRecovered = true
        testScheduler.advanceTimeBy(2_001)
        settle()

        assertEquals(DOWN, engagement.relayRequests.last())
        val ids = controller.state.value.engagementOf(note.id).reactionEvents.map { it.id }.toSet()
        assertEquals(setOf("earlier", "now", "late"), ids)
    }

    @Test
    fun switchingKindsKeepsRetryingReceivedLikes() = runTest {
        val relay = FakeRelay(reaction("got", targetId = "t", createdAt = epochOf(sep3)))
        relay.downRelay = DOWN
        relay.downRelayEvents += reaction("late", targetId = "t", createdAt = epochOf(sep2))
        val controller = controller(relay)
        controller.setKinds(setOf(JournalActivityKind.ReceivedLike))
        // 全リレーを対象にした取得（リレー未選択）で確かめる。
        controller.setRelayUrl(null)
        settle()

        // 取り直しを待っている間に種類を足す。もらったいいねは取得済みなので取り直さないが、待っている取り直しは続ける。
        controller.setKinds(setOf(JournalActivityKind.Post, JournalActivityKind.ReceivedLike))
        settle()
        relay.downRelayRecovered = true
        testScheduler.advanceTimeBy(2_001)
        settle()

        assertTrue(
            relay.requests.any { it.target == RelayTarget.Single(DOWN) && JournalActivityKind.ReceivedLike in it.kinds },
        )
        assertEquals(setOf("got", "late"), controller.state.value.timeline.eventIdsOn(sep1.daysOfMonthUntil(sep15)))
    }

    @Test
    fun leavingTheMonthStopsItsRetries() = runTest {
        val relay = FakeRelay(reaction("got", targetId = "t", createdAt = epochOf(sep3)))
        relay.downRelay = DOWN
        val controller = controller(relay)
        controller.setKinds(setOf(JournalActivityKind.ReceivedLike))
        // 全リレーを対象にした取得（リレー未選択）で確かめる。
        controller.setRelayUrl(null)
        settle()

        controller.previousMonth()
        settle()
        relay.downRelayRecovered = true
        testScheduler.advanceTimeBy(60_000)
        settle()

        val retried = relay.requests.filter { it.target == RelayTarget.Single(DOWN) }
        assertTrue(retried.isNotEmpty())
        assertTrue(retried.all { request -> request.dates.all { it.month == kotlinx.datetime.Month.AUGUST } })
    }

    @Test
    fun refreshKeepsRowsWhenARelayDidNotFinish() = runTest {
        val onlyOnDown = reaction("only-on-down", targetId = "t", createdAt = epochOf(sep3))
        val relay = FakeRelay(onlyOnDown)
        val controller = controller(relay, showCalendar = false)
        controller.setKinds(setOf(JournalActivityKind.ReceivedLike))
        // 全リレーを対象にした取得（リレー未選択）で確かめる。
        controller.setRelayUrl(null)
        settle()
        assertEquals(setOf("only-on-down"), controller.state.value.timeline.eventIdsOn(listOf(sep3)))

        // 更新の時点では、このいいねがあるリレーだけが落ちている。
        relay.events.clear()
        relay.downRelay = DOWN
        controller.refresh()
        settle()

        assertEquals(setOf("only-on-down"), controller.state.value.timeline.eventIdsOn(listOf(sep3)))
    }

    @Test
    fun engagementFromARelayThatWasDownIsAddedWithoutDoubleCounting() = runTest {
        val note = post(hexId(1))
        val shared = reaction("shared", targetId = note.id)
        val engagement = FakeEngagement(listOf(shared))
        engagement.downRelay = DOWN
        engagement.downRelayEvents += reaction("late", targetId = note.id, pubkey = "someone-else")
        val controller = controller(FakeRelay(note), engagement)
        controller.setRelayUrl("wss://memo")
        settle()
        controller.setVisibleNoteIds(setOf(note.id))
        settle()
        assertEquals(1, controller.state.value.engagementOf(note.id).summary.reactionCount)

        engagement.downRelayRecovered = true
        testScheduler.advanceTimeBy(2_001)
        settle()

        assertEquals(listOf<String?>("wss://memo", DOWN), engagement.relayRequests)
        assertEquals(2, controller.state.value.engagementOf(note.id).summary.reactionCount)
        // 取り直しで全リレーの分がそろったので、次に表示しても取り直さない。
        controller.setVisibleNoteIds(emptySet())
        controller.setVisibleNoteIds(setOf(note.id))
        settle()
        assertEquals(2, engagement.requests.size)
    }

    @Test
    fun switchingRelaysKeepsReferencedEvents() = runTest {
        val relay = FakeRelay(reply("child", parentId = hexId(20), createdAt = epochOf(sep3)))
        val references = FakeReferences(post(hexId(20), pubkey = OTHER))
        val controller = controller(relay, references = references)
        controller.setRelayUrl("wss://a")
        settle()

        controller.setRelayUrl("wss://b")
        settle()

        assertEquals(1, references.requests.size)
        assertEquals(setOf(hexId(20)), controller.state.value.referencedEvents.keys)
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
    fun receivedLikesComeFromTheSelectedRelayForTheWholeMonth() = runTest {
        val relay = FakeRelay(reaction("got", targetId = "t", createdAt = epochOf(sep3)))
        val controller = controller(relay)
        controller.setKinds(setOf(JournalActivityKind.ReceivedLike))
        controller.setRelayUrl("wss://memo")
        settle()

        val receivedRequests = relay.requests.filter { JournalActivityKind.ReceivedLike in it.kinds }
        assertEquals(1, receivedRequests.size)
        assertEquals(RelayTarget.Single("wss://memo"), receivedRequests.single().target)
        assertEquals(15, receivedRequests.single().dates.size)
        assertEquals(mapOf(sep3 to 1), controller.state.value.entryCountsByDate)
    }

    @Test
    fun backfillFetchesReferencesOnceAfterTheWholeMonth() = runTest {
        val relay = FakeRelay(
            reply("today", parentId = hexId(20), createdAt = epochOf(sep15)),
            reply("early", parentId = hexId(21), createdAt = epochOf(sep3)),
            reply("first", parentId = hexId(22), createdAt = epochOf(sep1)),
        )
        val references = FakeReferences(
            post(hexId(20), pubkey = OTHER),
            post(hexId(21), pubkey = OTHER),
            post(hexId(22), pubkey = OTHER),
        )
        val controller = controller(relay, references = references)
        controller.setRelayUrl("wss://memo")
        settle()

        // 選択日の分は先に取り、残りの日の分は日ごとではなく月の取得を終えてから1回で取る。
        assertEquals(listOf(setOf(hexId(20)), setOf(hexId(21), hexId(22))), references.requests.map { it.first })
        assertTrue(references.requests.all { it.second == "wss://memo" })
        assertEquals(setOf(hexId(20), hexId(21), hexId(22)), controller.state.value.referencedEvents.keys)
    }

    @Test
    fun referencesAlreadyInTheJournalAreNotFetched() = runTest {
        val parent = post(hexId(30), createdAt = epochOf(sep1))
        val relay = FakeRelay(
            parent,
            reply("child", parentId = parent.id, createdAt = epochOf(sep3)),
            reaction("got", targetId = parent.id, createdAt = epochOf(sep3)),
        )
        val references = FakeReferences()
        val controller = controller(relay, references = references)
        controller.setKinds(setOf(JournalActivityKind.Post, JournalActivityKind.Reply, JournalActivityKind.ReceivedLike))
        controller.setRelayUrl("wss://memo")
        settle()

        assertEquals(emptyList(), references.requests)
        assertEquals(parent, controller.state.value.referencedEvents[parent.id])
    }

    @Test
    fun theRestOfTheMonthIsFetchedInOneRequest() = runTest {
        val relay = FakeRelay(post("first", createdAt = epochOf(sep1)), post("early", createdAt = epochOf(sep3)))
        val controller = controller(relay)
        controller.setRelayUrl("wss://memo")
        settle()

        // 選択日（今日）を先に1回、残りの日をまとめて1回。
        assertEquals(listOf(listOf(sep15), sep1.daysOfMonthUntil(LocalDate(2026, 9, 14))), relay.requests.map { it.dates })
        assertTrue(controller.state.value.coverage.hasLoadedMonth(sep1, controller.state.value.kinds, sep15, setOf(OWNER)))
        assertEquals(setOf("first", "early"), controller.state.value.timeline.eventIdsOn(listOf(sep1, sep3)))
    }

    @Test
    fun aCutOffRangeFetchesOlderPagesUntilTheMonthIsComplete() = runTest {
        val relay = FakeRelay(
            post("sep3", createdAt = epochOf(sep3)),
            post("sep2", createdAt = epochOf(sep2)),
            post("sep1", createdAt = epochOf(sep1)),
        )
        relay.pageLimit = 2
        val controller = controller(relay)
        controller.setRelayUrl("wss://memo")
        settle()

        assertEquals(4, relay.requests.size)
        val state = controller.state.value
        assertTrue(state.coverage.hasLoadedMonth(sep1, state.kinds, sep15, setOf(OWNER)))
        assertEquals(setOf("sep1", "sep2", "sep3"), state.timeline.eventIdsOn(listOf(sep1, sep2, sep3)))
    }

    @Test
    fun refreshReplacesADaySplitAcrossPagesOnlyOnceItIsComplete() = runTest {
        val deleted = post("deleted", createdAt = epochOf(sep2, hour = 8))
        val relay = FakeRelay(
            post("sep3", createdAt = epochOf(sep3)),
            post("sep2-late", createdAt = epochOf(sep2, hour = 14)),
            post("sep2-early", createdAt = epochOf(sep2, hour = 10)),
            deleted,
        )
        relay.pageLimit = 2
        val controller = controller(relay)
        controller.setRelayUrl("wss://memo")
        settle()
        assertEquals(setOf("sep2-late", "sep2-early", "deleted"), controller.state.value.timeline.eventIdsOn(listOf(sep2)))

        relay.events -= deleted
        controller.toggleCalendar()
        controller.refresh()
        settle()

        // 9/2 は2ページにまたがる。前のページで足した行を、その日を置き換えるときに消してはいけない。
        assertEquals(setOf("sep2-late", "sep2-early"), controller.state.value.timeline.eventIdsOn(listOf(sep2)))
    }

    @Test
    fun aCutOffDayIsNotRecordedAsLoadedAndIsCompletedByTheMonth() = runTest {
        val relay = FakeRelay(
            post("late", createdAt = epochOf(sep15, hour = 11)),
            post("middle", createdAt = epochOf(sep15, hour = 10)),
            post("early", createdAt = epochOf(sep15, hour = 9)),
        )
        relay.pageLimit = 2
        val controller = controller(relay)
        controller.setRelayUrl("wss://memo")
        settle()

        val state = controller.state.value
        // 選択日の取得は打ち切られたので取得済みにせず、月の範囲の取得で続きを取る。
        assertEquals(setOf("late", "middle", "early"), state.visibleEntries.map { it.event.id }.toSet())
        assertTrue(state.coverage.hasLoadedMonth(sep1, state.kinds, sep15, setOf(OWNER)))
    }

    @Test
    fun ownJournalWithoutASignerShowsAnError() = runTest {
        val controller = JournalController(
            scope = backgroundScope,
            clock = utcClock(),
            eventSource = FakeRelay(),
            engagementSource = FakeEngagement(emptyList()),
            referenceSource = NoReferences,
            profileSource = NoProfiles,
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
        // 誰も選んでいなければ、追加した人だけを選んで表示する。
        assertEquals(setOf(friend), controller.state.value.selectedMembers)
        assertEquals(listOf("theirs"), controller.state.value.visibleEntries.map { it.event.id })
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
    fun addingAMemberKeepsTheCurrentSelection() = runTest {
        val friend = hexId(1)
        val other = hexId(2)
        val relay = FakeRelay(post("mine"), post("theirs", pubkey = friend), post("other", pubkey = other))
        val controller = controller(relay, storage = FakeMemberStorage(friend))
        controller.setRelayUrl("wss://memo")
        settle()
        controller.toggleMemberSelection(OWNER)
        controller.toggleMemberSelection(friend)
        settle()

        // 選んでいる人がいれば、その選択に追加した人を足す。
        controller.addMember(other)
        settle()
        assertEquals(setOf(OWNER, friend, other), controller.state.value.selectedMembers)
        assertEquals(setOf("mine", "theirs", "other"), controller.state.value.visibleEntries.map { it.event.id }.toSet())
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
            referenceSource = NoReferences,
            profileSource = NoProfiles,
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
        assertEquals(listOf("friend"), controller.state.value.visibleEntries.map { it.event.id })
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
        val controller = controller(FakeRelay(), interactions = { _, _, _ -> fetches += 1; interactions })
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

    @Test
    fun candidatesAreShownFromTheEventsReceivedSoFar() = runTest {
        val alice = hexId(1)
        val bob = hexId(2)
        val gate = CompletableDeferred<Unit>()
        val controller = controller(
            FakeRelay(),
            interactions = { _, _, onEvent ->
                // 応答の早いリレーの分が届いたあと、応答しないリレーを待っている。
                val early = reaction("l1", targetId = "t1", pubkey = alice, targetAuthor = OWNER)
                onEvent(early)
                gate.await()
                val late = listOf(
                    reaction("l2", targetId = "t2", pubkey = bob, targetAuthor = OWNER),
                    reaction("l3", targetId = "t3", pubkey = bob, targetAuthor = OWNER),
                )
                late.forEach(onEvent)
                listOf(early) + late
            },
        )
        controller.loadMemberCandidates()
        settle()
        testScheduler.advanceTimeBy(1_000)
        settle()

        // 取得の途中でも、届いた分で並べて出す。
        val partial = controller.state.value.memberCandidates
        assertEquals(listOf(JournalMemberCandidate(alice, 1)), partial.candidates)
        assertTrue(partial.isLoading)
        assertNull(partial.fetchedAtMillis)

        gate.complete(Unit)
        settle()
        val done = controller.state.value.memberCandidates
        assertEquals(listOf(JournalMemberCandidate(bob, 2), JournalMemberCandidate(alice, 1)), done.candidates)
        assertFalse(done.isLoading)
    }

    /** `advanceUntilIdle`は前景の処理がなくなると止まり`backgroundScope`を進めないため、現在時刻の処理をすべて実行する。 */
    private fun TestScope.settle() = testScheduler.runCurrent()

    private fun TestScope.controller(
        relay: FakeRelay,
        engagement: FakeEngagement = FakeEngagement(emptyList()),
        clock: JournalClock = utcClock(),
        storage: JournalMemberStorage = FakeMemberStorage(),
        interactions: JournalInteractionSource = JournalInteractionSource { _, _, _ -> emptyList() },
        references: JournalReferenceSource = NoReferences,
        profiles: JournalProfileSource = NoProfiles,
        showCalendar: Boolean = true,
    ) = JournalController(
        accountSession = session(),
        scope = backgroundScope,
        clock = clock,
        eventSource = relay,
        engagementSource = engagement,
        referenceSource = references,
        profileSource = profiles,
        memberStorage = storage,
        interactionSource = interactions,
        // 既存のテストは日ごとの表示で確かめる。
        initialShowCalendar = showCalendar,
    )

    /** プロフィールが届かない（監視は続く）取得元。 */
    private object SilentProfiles : JournalProfileSource {
        override fun cached(pubkeys: Set<String>): Map<String, NostrProfile> = emptyMap()
        override fun observe(pubkeys: Set<String>): Flow<Map<String, NostrProfile>> = MutableStateFlow(emptyMap())
        override suspend fun ensure(pubkeys: Set<String>, relayHint: String?) = Unit
    }

    /** 指定した人のプロフィールを持っているプロフィール取得元。 */
    private class KnownProfiles(vararg pubkeys: String) : JournalProfileSource {
        private val profiles = pubkeys.associateWith { NostrProfile(name = "name-$it") }
        override fun cached(pubkeys: Set<String>): Map<String, NostrProfile> = emptyMap()
        override fun observe(pubkeys: Set<String>): Flow<Map<String, NostrProfile>> =
            flowOf(profiles.filterKeys { it in pubkeys })
        override suspend fun ensure(pubkeys: Set<String>, relayHint: String?) = Unit
    }

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

    /**
     * フィルターに合うイベントを返すリレー。[complete]がfalseならタイムアウト扱い。
     * [pageLimit]に届くと新しい順にその件数だけ返し、返した最古の時刻で打ち切ったことにする。
     */
    private inner class FakeRelay(vararg initial: NostrEvent) : JournalEventSource {
        val events = initial.toMutableList()
        val requests = mutableListOf<JournalFetchRequest>()
        var complete = true
        var pageLimit: Int? = null
        var gate: CompletableDeferred<Unit>? = null
        /** 2回目以降（月の残りの取得）だけを止める。 */
        var backfillGate: CompletableDeferred<Unit>? = null
        /** 有効な全リレーへの取得で取り終えないリレー。このリレーだけへの取得は[downRelayRecovered]まで失敗する。 */
        var downRelay: String? = null
        var downRelayRecovered = false
        /** [downRelay]が拒否を返す（取り直さない）。 */
        var downRelayRefuses = false
        /** [downRelay]にだけあるイベント。 */
        val downRelayEvents = mutableListOf<NostrEvent>()

        override suspend fun fetch(request: JournalFetchRequest): JournalFetchResult {
            requests += request
            gate?.await()
            if (requests.size > 1) backfillGate?.await()
            val down = downRelay
            val failed = if (down == null || downRelayRefuses) emptySet() else setOf(down)
            if (down != null && request.target == RelayTarget.Single(down)) {
                if (!downRelayRecovered) return JournalFetchResult(request, emptyList(), complete = false, retryRelays = failed)
                return JournalFetchResult(request, downRelayEvents.filter { event -> request.filters.any { it.matches(event) } }, true)
            }
            if (down != null && request.target == RelayTarget.AllEnabled) {
                val matched = events.filter { event -> request.filters.any { it.matches(event) } }
                return JournalFetchResult(request, matched, complete, allRelaysFinished = false, retryRelays = failed)
            }
            val matched = events
                .filter { event -> request.filters.any { it.matches(event) } }
                .sortedByDescending { it.createdAt }
            val limit = pageLimit
            // 実際のリレーと同じく、上限ちょうどの件数が返ったら打ち切られたものとみなす。
            if (limit == null || matched.size < limit) return JournalFetchResult(request, matched, complete)
            val page = matched.take(limit)
            return JournalFetchResult(request, page, complete, truncatedAt = page.last().createdAt)
        }

        private fun NostrFilter.matches(event: NostrEvent): Boolean =
            event.createdAt >= (since ?: Long.MIN_VALUE) && event.createdAt <= (until ?: Long.MAX_VALUE) &&
                (kinds == null || event.kind in kinds.orEmpty()) &&
                (authors == null || event.pubkey in authors.orEmpty()) &&
                (pTags == null || event.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) in pTags.orEmpty() })
    }

    private class FakeEngagement(var events: List<NostrEvent>) : JournalEngagementSource {
        val requests = mutableListOf<Set<String>>()
        val relayRequests = mutableListOf<String?>()
        /** 取り終えないリレー。[downRelayEvents]はこのリレーにだけあり、[downRelayRecovered]まで取れない。 */
        var downRelay: String? = null
        var downRelayRecovered = false
        val downRelayEvents = mutableListOf<NostrEvent>()

        override suspend fun fetch(noteIds: Set<String>, relayUrl: String?, onEvent: (NostrEvent) -> Unit): JournalFetchStatus {
            requests += noteIds
            relayRequests += relayUrl
            val down = downRelay
            if (down != null && relayUrl == down) {
                if (!downRelayRecovered) return JournalFetchStatus(complete = false, retryRelays = setOf(down))
                (downRelayEvents + events).forEach(onEvent)
                return JournalFetchStatus(complete = true)
            }
            events.forEach(onEvent)
            return if (down == null) {
                JournalFetchStatus(complete = true)
            } else {
                JournalFetchStatus(complete = true, allRelaysFinished = false, retryRelays = setOf(down))
            }
        }
    }

    private object NoReferences : JournalReferenceSource {
        override suspend fun fetch(ids: Set<String>, relayUrl: String?, onEvent: (NostrEvent) -> Unit) = Unit
    }

    /** [known]だけを返す参照先の取得元。呼ばれた ID と指定のリレーを記録する。 */
    private class FakeReferences(vararg known: NostrEvent) : JournalReferenceSource {
        private val byId = known.associateBy { it.id }
        val requests = mutableListOf<Pair<Set<String>, String?>>()

        override suspend fun fetch(ids: Set<String>, relayUrl: String?, onEvent: (NostrEvent) -> Unit) {
            requests += ids to relayUrl
            ids.mapNotNull(byId::get).forEach(onEvent)
        }
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
