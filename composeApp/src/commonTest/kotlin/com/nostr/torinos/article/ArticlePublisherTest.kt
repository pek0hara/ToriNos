package com.nostr.torinos.article

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.writableRelayUrls
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlin.test.*

class ArticlePublisherTest {
    private val owner = "aa".repeat(32)
    @Test fun publicationUsesCurrentAppWriteRelays() = runTest {
        val store = ArticleDraftStore()
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        var entries = listOf(RelayEntry("wss://old", enabled = true))
        val sentTargets = mutableListOf<Set<String>>()
        val publisher = ArticlePublisher(owner, Signer(owner), store, { writableRelayUrls(entries) },
            nowSeconds = { 100 }, send = { _, targets ->
                sentTargets += targets.toSet()
                RelayPublishResult(targets.toSet(), emptyMap())
            })
        entries = listOf(
            RelayEntry("wss://write-only", enabled = true, read = false, write = true),
            RelayEntry("wss://read-write", enabled = true),
            RelayEntry("wss://read-only", enabled = true, write = false),
            RelayEntry("wss://disabled", enabled = false),
        )
        val result = publisher.publish(draft)
        val expected = setOf("wss://write-only", "wss://read-write")
        assertEquals(listOf(expected), sentTargets)
        assertEquals(expected, result.relays.keys)
        assertTrue(result.complete)
    }

    @Test fun noAppWriteRelaysPreventsSigningAndSending() = runTest {
        val store = ArticleDraftStore()
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        val signer = Signer(owner)
        val entries = listOf(
            RelayEntry("wss://read-only", enabled = true, write = false),
            RelayEntry("wss://disabled", enabled = false),
        )
        var sends = 0
        val publisher = ArticlePublisher(owner, signer, store, { writableRelayUrls(entries) },
            nowSeconds = { 100 }, send = { _, targets ->
                sends++
                RelayPublishResult(targets.toSet(), emptyMap())
            })
        val error = assertFailsWith<IllegalStateException> { publisher.publish(draft) }
        assertEquals("投稿先リレーを設定してください", error.message)
        assertEquals(0, signer.signatures)
        assertEquals(0, sends)
        assertTrue(store.read(owner).attempts.isEmpty())
        assertEquals(draft, store.read(owner).drafts[draft.draftId])
    }

    private class Signer(override val pubkey: String) : AccountSigner {
        var signatures = 0
        override fun encryptToSelf(plaintext: String) = error("unused")
        override fun decrypt(content: String, peerPubkey: String) = error("unused")
        override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent {
            signatures++
            return NostrEvent(signatures.toString().padStart(64, '0'), pubkey, createdAt!!, kind, tags, content, "signed")
        }
    }
    @Test fun matometrPublicationKeepsTheTagInMetadataWithoutAddingItToTheBody() = runTest {
        for (mode in listOf(ArticleDraftMode.MatometrStructured, ArticleDraftMode.MatometrMarkdown)) {
            val reference = MatometrReference("11".repeat(32))
            val body = "導入\n\n${reference.uri}"
            val store = ArticleDraftStore()
            val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "まとめ", mode = mode,
                bodyMarkdown = if (mode == ArticleDraftMode.MatometrStructured) "導入" else body,
                references = if (mode == ArticleDraftMode.MatometrStructured) listOf(reference) else emptyList()), null)
            val publisher = ArticlePublisher(owner, Signer(owner), store, { listOf("wss://one") },
                nowSeconds = { 100 }, send = { event, targets ->
                    assertEquals(body, event.content)
                    assertEquals(listOf(listOf("t", "matometr")), event.tags.filter { it.first() == "t" })
                    RelayPublishResult(targets.toSet(), emptyMap())
                })
            assertTrue(publisher.publish(draft).complete)
        }
    }
    @Test fun cleanupFailureKeepsAcceptanceAndRetriesOnlyDraftDeletion() = runTest {
        var failDelete = true
        var deletions = 0
        val transport = object : ArticleDraftTransport {
            override suspend fun load() = emptyList<ArticleDraft>()
            override suspend fun save(draft: ArticleDraft) = Unit
            override suspend fun delete(draft: ArticleDraft) { deletions++; check(!failDelete) { "delete rejected" } }
        }
        val store = ArticleDraftStore(transport = transport)
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        val signer = Signer(owner)
        var sends = 0
        val publisher = ArticlePublisher(owner, signer, store, { listOf("wss://one") }, nowSeconds = { 100 }, send = { _, targets ->
            sends++; RelayPublishResult(targets.toSet(), emptyMap())
        })
        val first = publisher.publish(draft)
        assertTrue(first.complete)
        assertNotNull(first.draftCleanupError)
        assertEquals(ArticleRelayState.Accepted, store.read(owner).attempts.getValue(first.id).relays.values.single().state)
        assertEquals(first.id, store.read(owner).archives[draft.articleIdentifier])
        assertTrue(draft.draftId in store.read(owner).drafts)
        failDelete = false
        val retried = publisher.retry(first.id)
        assertTrue(retried.complete)
        assertNull(retried.draftCleanupError)
        assertTrue(store.read(owner).drafts.isEmpty())
        assertEquals(1, sends)
        assertEquals(1, signer.signatures)
        assertEquals(2, deletions)
    }
    @Test fun cleanupRetryDoesNotDeleteEditsMadeAfterArticleAcceptance() = runTest {
        var failDelete = true
        var deletions = 0
        val transport = object : ArticleDraftTransport {
            override suspend fun load() = emptyList<ArticleDraft>()
            override suspend fun save(draft: ArticleDraft) = Unit
            override suspend fun delete(draft: ArticleDraft) { deletions++; check(!failDelete) }
        }
        val store = ArticleDraftStore(transport = transport)
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        var sends = 0
        val publisher = ArticlePublisher(owner, Signer(owner), store, { listOf("wss://one") }, nowSeconds = { 100 }, send = { _, targets ->
            sends++; RelayPublishResult(targets.toSet(), emptyMap())
        })
        val accepted = publisher.publish(draft)
        val newer = store.save(draft.copy(bodyMarkdown = "new edits"), draft.revision)
        failDelete = false
        val result = publisher.retry(accepted.id)
        assertNull(result.draftCleanupError)
        assertEquals(newer, store.read(owner).drafts[draft.draftId])
        assertEquals(1, sends)
        assertEquals(1, deletions)
    }
    @Test fun restartedSessionReSignsTheRelayDraftWithItsStableArticleAddressAndPublicationTime() = runTest {
        val remote = mutableMapOf<String, ArticleDraft>()
        val transport = object : ArticleDraftTransport {
            override suspend fun load() = remote.values.toList()
            override suspend fun save(draft: ArticleDraft) { remote[draft.draftId] = draft }
            override suspend fun delete(draft: ArticleDraft) { remote.remove(draft.draftId) }
        }
        val signer = Signer(owner)
        val firstStore = ArticleDraftStore(transport = transport)
        val draft = firstStore.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        val first = ArticlePublisher(owner, signer, firstStore, { listOf("wss://one") }, nowSeconds = { 100 }, send = { _, targets ->
            RelayPublishResult(emptySet(), targets.associateWith { "timeout" })
        }).publish(draft)
        val newSession = ArticleDraftStore(transport = transport)
        val resumed = newSession.loadDraft(owner, draft.draftId)!!
        assertTrue(newSession.read(owner).attempts.isEmpty())
        assertEquals(100L, resumed.originalPublishedAt)
        val next = ArticlePublisher(owner, signer, newSession, { listOf("wss://one") }, nowSeconds = { 101 }, send = { _, targets ->
            RelayPublishResult(targets.toSet(), emptyMap())
        }).publish(resumed)
        assertNotEquals(first.event.id, next.event.id)
        assertEquals(first.event.tags.first { it[0] == "d" }, next.event.tags.first { it[0] == "d" })
        assertEquals(first.event.tags.first { it[0] == "published_at" }, next.event.tags.first { it[0] == "published_at" })
        assertTrue(remote.isEmpty())
    }
    @Test fun differentPublisherInstancesCannotTransmitTheSameAddressTogether() = runTest {
        val storage = MemoryArticleStorage()
        val store = ArticleDraftStore(storage)
        val signer = Signer(owner)
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        val receipt = kotlinx.coroutines.CompletableDeferred<Unit>()
        val publisher = ArticlePublisher(owner, signer, store, { listOf("wss://one") }, nowSeconds = { 100 }, send = { _, targets ->
            receipt.await()
            RelayPublishResult(targets.toSet(), emptyMap())
        })
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { publisher.publish(draft) }
        var otherSent = false
        val other = ArticlePublisher(owner, signer, ArticleDraftStore(storage), { listOf("wss://one") }, nowSeconds = { 101 }, send = { _, _ ->
            otherSent = true
            RelayPublishResult(emptySet(), emptyMap())
        })
        assertFailsWith<IllegalStateException> { other.publish(draft) }
        assertFalse(otherSent)
        assertEquals(1, signer.signatures)
        receipt.complete(Unit)
        assertTrue(first.await().complete)
        assertTrue(store.read(owner).publishLeases.isEmpty())
    }
    @Test fun expiredPublisherCannotOverwriteTheRecoveredNewVersion() = runTest {
        val store = ArticleDraftStore(MemoryArticleStorage())
        val signer = Signer(owner)
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        var now = 100L
        lateinit var recovered: ArticlePublishAttempt
        val old = ArticlePublisher(owner, signer, store, { listOf("wss://one") }, nowSeconds = { now }, send = { _, targets ->
            now = 161
            val newer = store.save(draft.copy(bodyMarkdown = "new edits"), draft.revision)
            recovered = ArticlePublisher(owner, signer, store, { listOf("wss://one") }, nowSeconds = { now }, send = { _, relays ->
                RelayPublishResult(relays.toSet(), emptyMap())
            }).publish(newer)
            RelayPublishResult(targets.toSet(), emptyMap())
        })
        assertFailsWith<IllegalStateException> { old.publish(draft) }
        val data = store.read(owner)
        assertEquals(recovered.id, data.archives[draft.articleIdentifier])
        assertEquals(recovered.id, data.attempts.values.first { it.event.createdAt == 100L }.supersededBy)
        assertTrue(data.drafts.isEmpty())
    }
    @Test fun preparesInSessionMemoryBeforeNetworkAndRetriesIdenticalEvent() = runTest {
        val store = ArticleDraftStore(MemoryArticleStorage())
        val signer = Signer(owner)
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        val sent = mutableListOf<NostrEvent>()
        var accepted = false
        val sender: suspend (NostrEvent, Collection<String>) -> RelayPublishResult = { event, relays ->
            assertTrue(store.read(owner).attempts.values.any { it.event == event })
            sent += event
            if (accepted) RelayPublishResult(relays.toSet(), emptyMap()) else RelayPublishResult(emptySet(), relays.associateWith { "timeout" })
        }
        val first = ArticlePublisher(owner, signer, store, { listOf("wss://one") }, nowSeconds = { 100 }, send = sender).publish(draft)
        assertFalse(first.accepted)
        assertEquals(ArticleRelayState.Unknown, first.relays.values.single().state)
        assertEquals(draft.content, store.read(owner).drafts[draft.draftId]?.content)
        assertEquals(100L, store.read(owner).drafts[draft.draftId]?.originalPublishedAt)
        accepted = true
        val recovered = ArticlePublisher(owner, signer, store, { listOf("wss://one") }, send = sender).retry(first.id)
        assertTrue(recovered.complete)
        assertEquals(1, signer.signatures)
        assertEquals(sent.first(), sent.last())
        assertTrue(store.read(owner).drafts.isEmpty())
        assertEquals(first.id, store.read(owner).archives[draft.articleIdentifier])
    }
    @Test fun partialAcceptanceDeletesTheDraftAndRetrySendsOnlyToTheRest() = runTest {
        var deletions = 0
        val transport = object : ArticleDraftTransport {
            override suspend fun load() = emptyList<ArticleDraft>()
            override suspend fun save(draft: ArticleDraft) = Unit
            override suspend fun delete(draft: ArticleDraft) { deletions++ }
        }
        val store = ArticleDraftStore(transport = transport)
        val signer = Signer(owner)
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        val sentTargets = mutableListOf<Collection<String>>()
        var firstCall = true
        val publisher = ArticlePublisher(owner, signer, store, { listOf("wss://one", "wss://two") }, nowSeconds = { 100 }, send = { _, relays ->
            sentTargets += relays
            if (firstCall) { firstCall = false; RelayPublishResult(setOf("wss://one"), mapOf("wss://two" to "timeout")) }
            else RelayPublishResult(relays.toSet(), emptyMap())
        })
        val result = publisher.publish(draft)
        assertTrue(result.accepted)
        assertFalse(result.complete)
        assertNull(result.draftCleanupError)
        assertTrue(store.read(owner).drafts.isEmpty())
        assertEquals(1, deletions)
        // An edit after the partial publication is a new draft; resending the old version must not delete it.
        val newer = store.save(draft.copy(bodyMarkdown = "new edits"), null)
        val retried = publisher.retry(result.id)
        assertTrue(retried.complete)
        assertEquals(setOf("wss://two"), sentTargets.last().toSet())
        assertEquals(newer, store.read(owner).drafts[draft.draftId])
        assertEquals(1, signer.signatures)
        assertEquals(1, deletions)
    }
    @Test fun noAcceptanceKeepsTheDraft() = runTest {
        val store = ArticleDraftStore(MemoryArticleStorage())
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        val publisher = ArticlePublisher(owner, Signer(owner), store, { listOf("wss://one", "wss://two") }, nowSeconds = { 100 },
            send = { _, relays -> RelayPublishResult(emptySet(), relays.associateWith { "rejected" }, relays.toSet()) })
        assertFalse(publisher.publish(draft).accepted)
        assertEquals(draft.draftId, store.read(owner).drafts.values.single().draftId)
    }
    @Test fun editedPublicationSupersedesOldAttemptAndKeepsFirstPublicationTime() = runTest {
        val store = ArticleDraftStore(MemoryArticleStorage())
        val signer = Signer(owner)
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        var now = 100L
        val publisher = ArticlePublisher(owner, signer, store, { listOf("wss://one") }, nowSeconds = { now }, send = { _, relays -> RelayPublishResult(emptySet(), relays.associateWith { "timeout" }) })
        val first = publisher.publish(draft)
        val newer = store.save(draft.copy(bodyMarkdown = "new edits"), draft.revision)
        now = 101
        val second = publisher.publish(newer)
        assertNotEquals(first.event.id, second.event.id)
        assertEquals(first.event.tags.first { it[0] == "published_at" }, second.event.tags.first { it[0] == "published_at" })
        assertEquals(first.event.tags.first { it[0] == "d" }, second.event.tags.first { it[0] == "d" })
        assertTrue(second.event.createdAt > first.event.createdAt)
        assertFailsWith<IllegalStateException> { publisher.retry(first.id) }
    }
    @Test fun failedPreparationNeverSendsAndChangedWritePermissionIsRespected() = runTest {
        val storage = MemoryArticleStorage()
        val store = ArticleDraftStore(storage)
        val signer = Signer(owner)
        val draft = store.save(ArticleDraft(ownerPubkey = owner, title = "title", bodyMarkdown = "body"), null)
        var sends = 0
        var allowed = listOf("wss://one")
        val publisher = ArticlePublisher(owner, signer, store, { allowed }, nowSeconds = { 100 }, send = { _, relays -> sends++; RelayPublishResult(emptySet(), relays.associateWith { "timeout" }) })
        storage.failWrite = true
        assertFailsWith<IllegalStateException> { publisher.publish(draft) }
        assertEquals(0, sends)
        storage.failWrite = false
        val first = publisher.publish(draft)
        allowed = emptyList()
        val retry = publisher.retry(first.id)
        assertEquals(1, sends)
        assertFalse(retry.complete)
        assertEquals(ArticleRelayState.Unknown, retry.relays.values.single().state)
        assertTrue(retry.relays.values.single().reason.contains("書き込みが許可されていません"))
    }
}
