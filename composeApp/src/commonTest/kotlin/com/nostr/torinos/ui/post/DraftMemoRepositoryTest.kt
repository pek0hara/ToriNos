package com.nostr.torinos.ui.post

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftMemoRepositoryTest {
    @Test
    fun mergeReplacesAnEditedDraftWithTheLatestEvent() {
        val original = draft(eventId = "old-event", identifier = "memo-1", text = "変更前", createdAt = 100)
        val edited = draft(eventId = "new-event", identifier = "memo-1", text = "変更後", createdAt = 200)

        val result = mergeDraftMemos(listOf(original), listOf(edited))

        assertEquals(1, result.size)
        assertEquals("new-event", result.single().eventId)
        assertEquals("変更後", result.single().memo.text)
    }

    @Test
    fun mergeKeepsDraftsWithDifferentIdentifiersNewestFirst() {
        val first = draft(eventId = "event-1", identifier = "memo-1", text = "1件目", createdAt = 100)
        val second = draft(eventId = "event-2", identifier = "memo-2", text = "2件目", createdAt = 200)

        assertEquals(listOf("event-2", "event-1"), mergeDraftMemos(listOf(first), listOf(second)).map { it.eventId })
    }

    @Test
    fun mergeUsesTheLowestEventIdWhenTimestampsMatch() {
        val higherId = draft(eventId = "ff-event", identifier = "memo-1", text = "破棄される内容", createdAt = 100)
        val lowerId = draft(eventId = "00-event", identifier = "memo-1", text = "保持される内容", createdAt = 100)

        assertEquals("00-event", mergeDraftMemos(listOf(higherId), listOf(lowerId)).single().eventId)
    }

    @Test
    fun deletionReferencesTheEventAndItsAddress() {
        assertEquals(
            listOf(
                listOf("e", "event-1"),
                listOf("a", "$MEMO_EVENT_KIND:pubkey:memo-1"),
                listOf("k", MEMO_EVENT_KIND.toString()),
                listOf("client", "ToriNos"),
            ),
            draftDeletionTags(draft(eventId = "event-1", identifier = "memo-1", text = "", createdAt = 1)),
        )
        assertEquals(
            listOf(listOf("e", "event-2"), listOf("k", MEMO_EVENT_KIND.toString()), listOf("client", "ToriNos")),
            draftDeletionTags(draft(eventId = "event-2", identifier = null, text = "", createdAt = 1)),
        )
    }

    private val owner = "aa".repeat(32)
    private val relay = "wss://memo.example"
    private val signer = object : AccountSigner {
        override val pubkey = owner
        override suspend fun nip44Encrypt(plaintext: String, peerPubkey: String) = "encrypted:$plaintext"
        override suspend fun nip44Decrypt(content: String, peerPubkey: String) = content.removePrefix("encrypted:")
        override suspend fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent = error("unused")
    }
    private fun memoEvent(text: String) = NostrEvent("11".repeat(32), owner, 100, MEMO_EVENT_KIND,
        listOf(listOf("d", "memo-1"), listOf("k", "1")),
        "encrypted:" + encodePostDraft(PostMemoPayload(text = text, updatedAt = 100), owner), "sig")
    /** The signal stream stays open after FetchCompleted, as a fetch that ended by timeout has been seen to do. */
    private fun repository(outcome: RelayOutcome, events: List<NostrEvent>) = DraftMemoRepository(signer, openSession = { spec ->
        assertEquals(RelayTarget.Single(relay), spec.target)
        object : SubscriptionSession {
            override val id = "memo"
            override val signals = flow {
                events.forEach { emit(SubscriptionSignal.Event(relay, it, false)) }
                emit(SubscriptionSignal.FetchCompleted(mapOf(relay to outcome), outcome is RelayOutcome.TimedOut))
                awaitCancellation()
            }
            override suspend fun update(filters: List<com.nostr.torinos.model.NostrFilter>, target: RelayTarget) = Unit
            override suspend fun close() = Unit
        }
    })

    @Test
    fun loadEndsOnFetchCompletedAndReportsAFullAnswer() = runTest {
        val result = assertNotNullLoad(repository(RelayOutcome.Eose, listOf(memoEvent("下書き"))).loadAll(relay))
        assertEquals(listOf("下書き"), result.drafts.map { it.memo.text })
        assertTrue(result.complete)
    }

    @Test
    fun relayThatDidNotFinishIsReportedRatherThanLookingEmpty() = runTest {
        val empty = assertNotNullLoad(repository(RelayOutcome.Unavailable("offline"), emptyList()).loadAll(relay))
        assertTrue(empty.drafts.isEmpty())
        assertFalse(empty.complete)
        val partial = assertNotNullLoad(repository(RelayOutcome.TimedOut, listOf(memoEvent("届いた分"))).loadAll(relay))
        assertEquals(listOf("届いた分"), partial.drafts.map { it.memo.text })
        assertFalse(partial.complete)
    }

    @Test
    fun listStateShowsAnErrorForAnEmptyFailedLoadAndAWarningForAPartialOne() {
        assertEquals(DraftListState(error = "下書きを取得できませんでした（memo.example）", incompleteRelay = relay),
            DraftListState(isLoading = true).withLoaded(DraftMemoLoad(emptyList(), complete = false), relay))
        val partial = DraftListState(isLoading = true).withLoaded(DraftMemoLoad(listOf(draft("e", "m", "本文", 1)), complete = false), relay)
        assertNull(partial.error)
        assertEquals(relay, partial.incompleteRelay)
        assertEquals(1, partial.drafts.size)
        val full = partial.withLoaded(DraftMemoLoad(emptyList(), complete = true), relay)
        assertEquals(DraftListState(), full)
    }

    private fun assertNotNullLoad(load: DraftMemoLoad?) = kotlin.test.assertNotNull(load)

    private fun draft(eventId: String, identifier: String?, text: String, createdAt: Long) = DraftMemo(
        eventId = eventId,
        pubkey = "pubkey",
        memo = PostMemoData(
            text = text,
            imageUrls = emptyList(),
            replyToId = null,
            replyToPubkey = null,
            noteKind = 1,
            channelId = null,
            updatedAt = createdAt,
            identifier = identifier,
        ),
        createdAt = createdAt,
    )
}
