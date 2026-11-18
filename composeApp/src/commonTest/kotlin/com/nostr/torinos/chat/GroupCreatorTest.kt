package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupCreatorTest {
    private val relay = "wss://png.example.com"
    private val relayKey = "f".repeat(64)
    private val me = "a".repeat(64)
    private val uuid = "10000000-0000-4000-8000-000000000001"
    private val ref = GroupRef(relay, uuid)

    private class FakeJournal : CreationJournal {
        var stored: List<PendingCreation> = emptyList()
        override suspend fun load(): List<PendingCreation> = stored
        override suspend fun save(pending: List<PendingCreation>) { stored = pending }
    }

    private inner class FakeGateway : GroupCreationGateway {
        var now = 1_000L
        var signCount = 0
        val published = mutableListOf<NostrEvent>()
        val pauses = mutableListOf<Long>()
        /** 送るたびに先頭から使う。尽きたら受理。 */
        val outcomes = ArrayDeque<(NostrEvent) -> GroupPublishOutcome>()
        /** 受理した作成を、何回目の読み取りから見せるか（0 = すぐ）。 */
        var visibleAfterFetches = 0
        var fetches = 0
        var creatorIsMember = true
        val accepted = mutableListOf<NostrEvent>()

        override suspend fun sign(draft: GroupEventDraft): NostrEvent {
            signCount++
            return NostrEvent(signCount.toString().padStart(64, '0'), me, now, draft.kind, draft.tags, draft.content, "sig")
        }

        override suspend fun publish(ref: GroupRef, event: NostrEvent): GroupPublishOutcome {
            published += event
            val outcome = outcomes.removeFirstOrNull()?.invoke(event) ?: GroupPublishOutcome.Published(event)
            if (outcome is GroupPublishOutcome.Published) accepted += event
            return outcome
        }

        override suspend fun fetch(relayUrl: String, filters: List<NostrFilter>): List<NostrEvent> {
            fetches++
            if (fetches <= visibleAfterFetches) return emptyList()
            val create = accepted.firstOrNull { it.kind == GROUP_CREATE_KIND } ?: return emptyList()
            val id = create.tags.first { it[0] == "h" }[1]
            val name = (accepted.lastOrNull { it.kind == GROUP_EDIT_METADATA_KIND } ?: create).tags.firstOrNull { it[0] == "name" }?.get(1).orEmpty()
            return listOf(
                NostrEvent("m".repeat(64), relayKey, now, GROUP_METADATA_KIND, listOf(listOf("d", id), listOf("name", name), listOf("t", "stream")), "", "sig"),
                NostrEvent("r".repeat(64), relayKey, now, GROUP_MEMBERS_KIND, listOf(listOf("d", id)) + if (creatorIsMember) listOf(listOf("p", me)) else emptyList(), "", "sig"),
            ).filter { event -> filters.any { event.kind in it.kinds.orEmpty() } }
        }

        override suspend fun relayPubkey(relayUrl: String): String? = relayKey
        override fun now(): Long = now
        override suspend fun pause(millis: Long) { pauses += millis }
    }

    private val gateway = FakeGateway()
    private val journal = FakeJournal()
    private val creator = GroupCreator(gateway, journal, me)

    private fun spec(name: String = "release-notes", id: String = uuid) =
        NewGroupSpec(groupId = id, name = name, about = "", type = NewGroupType.Chat, visibility = NewGroupVisibility.Open)

    // ---- Buzz ----

    @Test
    fun buzzCreatePublishesOnceAndConfirmsMembership() = runTest {
        val result = creator.create(relay, GroupFlavor.Buzz, spec())

        val created = assertIs<GroupCreateResult.Created>(result)
        assertEquals(ref, created.ref)
        assertTrue(created.confirmed)
        assertEquals("release-notes", created.metadata?.name)
        assertEquals(listOf(GROUP_CREATE_KIND), gateway.published.map { it.kind })
        assertTrue(journal.stored.isEmpty())
    }

    @Test
    fun buzzCreateSavesTheSignedEventBeforeSending() = runTest {
        var journalAtPublish: List<PendingCreation> = emptyList()
        gateway.outcomes += { event -> journalAtPublish = journal.stored; GroupPublishOutcome.Published(event) }

        creator.create(relay, GroupFlavor.Buzz, spec())

        assertEquals(uuid, journalAtPublish.single().groupId)
        assertEquals(gateway.published.single(), journalAtPublish.single().createEvent)
    }

    @Test
    fun buzzNameTakenClearsTheJournalAndReportsIt() = runTest {
        gateway.outcomes += { GroupPublishOutcome.Rejected("invalid: an open channel named \"release-notes\" already exists") }

        val result = creator.create(relay, GroupFlavor.Buzz, spec())

        assertEquals(CreateRejection.NameTaken, assertIs<GroupCreateResult.Rejected>(result).kind)
        assertTrue(journal.stored.isEmpty())
    }

    @Test
    fun buzzLostAnswerKeepsTheJournalAndResumeResendsTheSameEvent() = runTest {
        gateway.outcomes += { GroupPublishOutcome.Failed("送信がタイムアウトしました") }

        assertIs<GroupCreateResult.Unfinished>(creator.create(relay, GroupFlavor.Buzz, spec()))
        val pending = creator.pending().single()
        assertEquals("release-notes", pending.name)

        val resumed = creator.resume(pending)

        assertIs<GroupCreateResult.Created>(resumed)
        assertEquals(2, gateway.published.size)
        assertEquals(gateway.published[0], gateway.published[1])
        assertEquals(1, gateway.signCount)
        assertTrue(journal.stored.isEmpty())
    }

    @Test
    fun buzzResumeAfterFourteenMinutesChecksFirstAndDoesNotSendWhenItExists() = runTest {
        gateway.outcomes += { event -> gateway.accepted += event; GroupPublishOutcome.Failed("timeout") }
        creator.create(relay, GroupFlavor.Buzz, spec())
        gateway.now += 14 * 60

        val resumed = creator.resume(creator.pending().single())

        assertTrue(assertIs<GroupCreateResult.Created>(resumed).confirmed)
        assertEquals(1, gateway.published.size)
    }

    @Test
    fun buzzResumeAfterFourteenMinutesSignsAgainWithTheSameIdWhenItDoesNotExist() = runTest {
        gateway.outcomes += { GroupPublishOutcome.Failed("timeout") }
        creator.create(relay, GroupFlavor.Buzz, spec())
        gateway.now += 14 * 60

        val resumed = creator.resume(creator.pending().single())

        assertIs<GroupCreateResult.Created>(resumed)
        assertEquals(2, gateway.published.size)
        assertNotEquals(gateway.published[0].id, gateway.published[1].id)
        assertEquals(gateway.published[0].tags, gateway.published[1].tags)
    }

    @Test
    fun buzzTakenIdCountsAsCreatedOnlyWhenTheChannelIsMine() = runTest {
        gateway.outcomes += { event -> gateway.accepted += event; GroupPublishOutcome.Rejected("duplicate: channel already exists") }
        assertIs<GroupCreateResult.Created>(creator.create(relay, GroupFlavor.Buzz, spec()))

        val other = FakeGateway().apply {
            creatorIsMember = false
            outcomes += { event -> accepted += event; GroupPublishOutcome.Rejected("duplicate: channel already exists") }
        }
        val otherJournal = FakeJournal()
        val result = GroupCreator(other, otherJournal, me).create(relay, GroupFlavor.Buzz, spec())

        assertEquals(CreateRejection.IdTaken, assertIs<GroupCreateResult.Rejected>(result).kind)
        assertTrue(otherJournal.stored.isEmpty())
    }

    @Test
    fun buzzConfirmationRetriesAndAcceptedCreateIsStillSuccessWhenUnconfirmed() = runTest {
        gateway.visibleAfterFetches = 1
        assertTrue(assertIs<GroupCreateResult.Created>(creator.create(relay, GroupFlavor.Buzz, spec())).confirmed)
        assertEquals(listOf(250L), gateway.pauses)

        val slow = FakeGateway().apply { visibleAfterFetches = 100 }
        val slowJournal = FakeJournal()
        val result = assertIs<GroupCreateResult.Created>(GroupCreator(slow, slowJournal, me).create(relay, GroupFlavor.Buzz, spec()))

        assertEquals(false, result.confirmed)
        assertNull(result.metadata)
        assertEquals(listOf(250L, 750L, 1500L), slow.pauses)
        assertTrue(slowJournal.stored.isEmpty())
    }

    @Test
    fun discardRemovesOnlyThatCreation() = runTest {
        gateway.outcomes += { GroupPublishOutcome.Failed("timeout") }
        creator.create(relay, GroupFlavor.Buzz, spec())
        gateway.outcomes += { GroupPublishOutcome.Failed("timeout") }
        creator.create("wss://other.example.com", GroupFlavor.Buzz, spec(name = "second", id = "20000000-0000-4000-8000-000000000002"))
        assertEquals(2, creator.pending().size)

        creator.discard(creator.pending().first { it.relayUrl == relay })

        assertEquals("second", creator.pending().single().name)
    }

    // ---- 標準の NIP-29 ----

    @Test
    fun standardCreateSendsCreateThenMetadata() = runTest {
        val result = creator.create(relay, GroupFlavor.Standard, spec(id = "abc123"))

        val created = assertIs<GroupCreateResult.Created>(result)
        assertEquals(GroupRef(relay, "abc123"), created.ref)
        assertEquals(listOf(GROUP_CREATE_KIND, GROUP_EDIT_METADATA_KIND), gateway.published.map { it.kind })
        assertEquals(listOf(listOf("h", "abc123")), gateway.published[0].tags)
        assertEquals("release-notes", created.metadata?.name)
        assertTrue(journal.stored.isEmpty())
    }

    @Test
    fun standardCreateRejectionStopsBeforeMetadata() = runTest {
        gateway.outcomes += { GroupPublishOutcome.Rejected("restricted: not allowed") }

        val result = creator.create(relay, GroupFlavor.Standard, spec(id = "abc123"))

        assertEquals(CreateRejection.NotAllowed, assertIs<GroupCreateResult.Rejected>(result).kind)
        assertEquals(1, gateway.published.size)
        assertTrue(journal.stored.isEmpty())
    }

    @Test
    fun standardMetadataFailureResumesFromMetadata() = runTest {
        gateway.outcomes += { event -> GroupPublishOutcome.Published(event) }
        gateway.outcomes += { GroupPublishOutcome.Failed("timeout") }

        assertIs<GroupCreateResult.Unfinished>(creator.create(relay, GroupFlavor.Standard, spec(id = "abc123")))
        val pending = creator.pending().single()
        assertEquals(CreationStage.Metadata, pending.stage)

        val resumed = creator.resume(pending)

        assertIs<GroupCreateResult.Created>(resumed)
        assertEquals(listOf(GROUP_CREATE_KIND, GROUP_EDIT_METADATA_KIND, GROUP_EDIT_METADATA_KIND), gateway.published.map { it.kind })
        assertTrue(journal.stored.isEmpty())
    }

    @Test
    fun standardResumeOfAnUnansweredCreateTreatsDuplicateAsDone() = runTest {
        gateway.outcomes += { GroupPublishOutcome.Failed("timeout") }
        creator.create(relay, GroupFlavor.Standard, spec(id = "abc123"))
        gateway.outcomes += { event -> gateway.accepted += event; GroupPublishOutcome.Rejected("duplicate: group already exists") }

        val resumed = creator.resume(creator.pending().single())

        assertIs<GroupCreateResult.Created>(resumed)
        assertEquals(GROUP_EDIT_METADATA_KIND, gateway.published.last().kind)
    }
}
