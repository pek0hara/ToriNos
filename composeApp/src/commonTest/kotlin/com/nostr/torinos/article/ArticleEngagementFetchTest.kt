package com.nostr.torinos.article

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArticleEngagementFetchTest {
    private val filters = listOf(NostrFilter(kinds = listOf(7), aTags = listOf("30023:a:x"), limit = 500))

    @Test
    fun withoutOwnEventsOnlyTheEngagementQueryIsSent() = runTest {
        val reaction = event("r1", kind = 7, pubkey = "alice")
        val opener = FakeOpener(
            completedSession(SubscriptionSignal.Event("relay", reaction, false), SubscriptionSignal.Eose("relay")),
        )
        val snapshots = mutableListOf<Pair<List<String>, Boolean>>()

        fetchArticleEngagementEvents(filters, ownPubkey = "me", open = opener::open) { events, complete ->
            snapshots += events.map { it.id } to complete
        }

        assertEquals(1, opener.specs.size)
        assertEquals(filters, opener.specs.single().filters)
        assertEquals(listOf(listOf("r1") to false, listOf("r1") to true), snapshots)
        assertTrue(opener.sessions.all { it.closed })
    }

    @Test
    fun firstEoseWithoutEventsStillDeliversAnEmptySnapshot() = runTest {
        // 件数0の記事でも、最初のリレーの応答で「読み込み中」を終えられるようにする。
        val opener = FakeOpener(completedSession(SubscriptionSignal.Eose("fast")))
        val snapshots = mutableListOf<Pair<Int, Boolean>>()

        fetchArticleEngagementEvents(filters, ownPubkey = null, open = opener::open) { events, complete ->
            snapshots += events.size to complete
        }

        assertEquals(listOf(0 to false, 0 to true), snapshots)
    }

    @Test
    fun ownEventsAreCheckedForDeletionAfterTheQueryCompletes() = runTest {
        val own = event("mine", kind = 7, pubkey = "me")
        val ownComment = event("comment", kind = 1111, pubkey = "me")
        val other = event("r1", kind = 7, pubkey = "alice")
        val deletion = event("del", kind = 5, pubkey = "me", tags = listOf(listOf("e", "mine")))
        val opener = FakeOpener(
            completedSession(
                SubscriptionSignal.Event("relay", own, false),
                SubscriptionSignal.Event("relay", ownComment, false),
                SubscriptionSignal.Event("relay", other, false),
            ),
            completedSession(SubscriptionSignal.Event("relay", deletion, false)),
        )
        var finalEvents: List<NostrEvent> = emptyList()

        fetchArticleEngagementEvents(filters, ownPubkey = "me", open = opener::open) { events, complete ->
            if (complete) finalEvents = events
        }

        val check = opener.specs[1].filters.single()
        assertEquals(listOf(5), check.kinds)
        assertEquals(listOf("me"), check.authors)
        assertEquals(listOf("mine", "comment"), check.eTags)
        assertEquals(listOf("mine", "comment", "r1", "del"), finalEvents.map { it.id })
        assertEquals(listOf("comment", "r1"), withoutDeletedEvents(finalEvents, finalEvents).filter { it.kind != 5 }.map { it.id })
        assertTrue(opener.sessions.all { it.closed })
    }

    @Test
    fun failedDeletionCheckStillDeliversTheFinalSnapshot() = runTest {
        val own = event("mine", kind = 7, pubkey = "me")
        val opener = FakeOpener(completedSession(SubscriptionSignal.Event("relay", own, false)))
        opener.failAfterFirst = true
        var finalEvents: List<String>? = null

        fetchArticleEngagementEvents(filters, ownPubkey = "me", open = opener::open) { events, complete ->
            if (complete) finalEvents = events.map { it.id }
        }

        assertEquals(listOf("mine"), finalEvents)
    }

    @Test
    fun ownEventIdsAreLimited() {
        val events = (1..600).map { event("e$it", kind = 7, pubkey = "me") } + event("x", kind = 7, pubkey = "alice")
        val ids = ownEventIdsForDeletionCheck(events, "me")
        assertEquals(500, ids.size)
        assertEquals("e1", ids.first())
        assertEquals(500, ownDeletionCheckFilter("me", ids).limit)
    }

    @Test
    fun subscriptionIdsFitRelayLimits() {
        listOf("article", "article-engagement", "article-detail-quote", "article-own-deletions").forEach { prefix ->
            repeat(50) {
                val id = articleSubscriptionId(prefix)
                assertTrue(id.length <= 50, id)
                assertTrue(id.startsWith("$prefix-"), id)
            }
        }
    }

    private fun completedSession(vararg signals: SubscriptionSignal): FakeSession =
        FakeSession(flowOf(*signals, SubscriptionSignal.FetchCompleted(mapOf("relay" to RelayOutcome.Eose), false)))

    private class FakeOpener(vararg sessions: FakeSession) {
        private val pending = ArrayDeque(sessions.toList())
        val sessions = sessions.toList()
        val specs = mutableListOf<SubscriptionSpec>()
        var failAfterFirst = false

        suspend fun open(spec: SubscriptionSpec): SubscriptionSession {
            specs += spec
            if (failAfterFirst && specs.size > 1) error("offline")
            return pending.removeFirst()
        }
    }

    private class FakeSession(override val signals: Flow<SubscriptionSignal>) : SubscriptionSession {
        override val id = "test"
        var closed = false
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() { closed = true }
    }

    private fun event(id: String, kind: Int, pubkey: String, tags: List<List<String>> = emptyList()) =
        NostrEvent(id = id, pubkey = pubkey, createdAt = 10, kind = kind, tags = tags, content = "+", sig = "")
}
