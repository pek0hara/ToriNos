package com.nostr.torinos.article

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.crypto.Nip44
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.*

class ArticleDraftRepositoryTest {
    @Test fun deletedMatometrTagSurvivesRelayDraftRoundTripAndPublication() = runTest {
        val signer = Signer(owner)
        lateinit var event: NostrEvent
        val repository = ArticleDraftRepository(signer, { setOf(relay) }, { listOf(relay) },
            send = { saved, targets -> event = saved; RelayPublishResult(targets.toSet(), emptyMap()) }, nowSeconds = { 100 })
        val draft = com.nostr.torinos.ui.article.updateArticleDraftTopics(
            ArticleDraft(ownerPubkey = owner, title = "まとめ", mode = ArticleDraftMode.MatometrStructured,
                references = listOf(MatometrReference("11".repeat(32))), topicsInput = "nostr"), "nostr")
        repository.save(draft)
        val restored = assertNotNull(decodeArticleDraft(event, signer))
        assertTrue(restored.isMatometr)
        assertFalse(restored.hasMatometrTag)
        assertEquals(draft.references, restored.references)
        assertEquals(draft.content, restored.content)
        val store = ArticleDraftStore(MemoryArticleStorage())
        val saved = store.save(restored, null)
        val published = ArticlePublisher(owner, signer, store, { listOf(relay) }, nowSeconds = { 100 },
            send = { _, targets -> RelayPublishResult(targets.toSet(), emptyMap()) }).publish(saved)
        assertFalse(published.event.tags.contains(listOf("t", "matometr")))
        assertTrue(published.event.tags.contains(listOf("t", "nostr")))
        assertFalse(published.event.content.endsWith("#matometr"))
    }

    private val owner = "aa".repeat(32)
    private val relay = "wss://draft.example"
    private open class Signer(override val pubkey: String) : AccountSigner {
        var signatures = 0
        override fun encryptToSelf(plaintext: String) = "encrypted:$plaintext"
        override fun decrypt(content: String, peerPubkey: String): String {
            require(peerPubkey == pubkey && content.startsWith("encrypted:"))
            return content.removePrefix("encrypted:")
        }
        override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent =
            NostrEvent((++signatures).toString().padStart(64, '0'), pubkey, createdAt!!, kind, tags, content, "signed")
    }
    private fun session(signals: List<SubscriptionSignal>) = object : SubscriptionSession {
        override val id = "test"
        override val signals = flowOf(*signals.toTypedArray())
        override suspend fun update(filters: List<com.nostr.torinos.model.NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() = Unit
    }
    @Test fun tenThousandJapaneseCharactersUseRealEncryptionAndOnlyOneCopyOfMarkdown() = runTest {
        val signer = object : Signer(owner) {
            override fun encryptToSelf(plaintext: String) = Nip44.encryptWithConversationKey(plaintext, ByteArray(32), ByteArray(32))
            override fun decrypt(content: String, peerPubkey: String) = Nip44.decryptWithConversationKey(content, ByteArray(32))
        }
        val refs = (1..50).map { MatometrReference(it.toString(16).padStart(64, '0'), commentaryMarkdown = "\r\n説明\n") }
        val base = ArticleDraft(ownerPubkey = owner, mode = ArticleDraftMode.MatometrStructured, references = refs)
        val structured = base.copy(bodyMarkdown = "あ".repeat(10_000 - base.content.length - 2))
        val normal = ArticleDraft(ownerPubkey = owner, bodyMarkdown = "あ".repeat(10_000))
        for (draft in listOf(normal, structured).map { it.copy(title = "題".repeat(200), summary = "要".repeat(500), topicsInput = "話".repeat(500)) }) {
            assertEquals(10_000, draft.content.length)
            assertNull(validateArticleDraft(draft))
            lateinit var saved: NostrEvent
            val repository = ArticleDraftRepository(signer, { setOf(relay) }, { listOf(relay) }, send = { event, targets ->
                saved = event; RelayPublishResult(targets.toSet(), emptyMap())
            }, nowSeconds = { 100 })
            repository.save(draft)
            val plaintext = signer.decrypt(saved.content, owner)
            assertTrue(plaintext.encodeToByteArray().size <= 65_535)
            val payload = articleJson.decodeFromString<ArticleDraftPayload>(plaintext)
            assertEquals("", payload.torinos!!.bodyMarkdown)
            assertTrue(payload.torinos.references.all { it.commentaryMarkdown.isEmpty() })
            val restored = decodeArticleDraft(saved, signer)!!
            assertEquals(draft.bodyMarkdown, restored.bodyMarkdown)
            assertEquals(draft.references, restored.references)
            assertEquals(draft.content, restored.content)
        }
    }
    @Test fun compactDraftsPreserveFreeMarkdownAndWhitespaceWhileLegacyDraftsStillLoad() = runTest {
        val signer = Signer(owner)
        val drafts = listOf(
            ArticleDraft(ownerPubkey = owner, bodyMarkdown = "    code\n"),
            ArticleDraft(ownerPubkey = owner, mode = ArticleDraftMode.MatometrMarkdown, bodyMarkdown = "\r\n導入\n#matometr\r\n"),
            ArticleDraft(ownerPubkey = owner, mode = ArticleDraftMode.MatometrStructured, bodyMarkdown = " \t\n",
                references = listOf(MatometrReference("11".repeat(32), commentaryMarkdown = "\r\n説明\n\n"),
                    MatometrReference("22".repeat(32), commentaryMarkdown = " \n"))),
        )
        for (draft in drafts) {
            lateinit var saved: NostrEvent
            val repository = ArticleDraftRepository(signer, { setOf(relay) }, { listOf(relay) }, send = { event, urls ->
                saved = event; RelayPublishResult(urls.toSet(), emptyMap())
            }, nowSeconds = { 100 })
            repository.save(draft)
            val restored = decodeArticleDraft(saved, signer)!!
            assertEquals(draft.bodyMarkdown, restored.bodyMarkdown)
            assertEquals(draft.references, restored.references)
            val payload = articleJson.decodeFromString<ArticleDraftPayload>(signer.decrypt(saved.content, owner))
            val legacy = payload.copy(torinos = draft, torinosContentLayout = null)
            val restoredLegacy = decodeArticleDraft(saved.copy(content = signer.encryptToSelf(articleJson.encodeToString(legacy))), signer)!!
            assertEquals(draft.bodyMarkdown, restoredLegacy.bodyMarkdown)
            assertEquals(draft.references, restoredLegacy.references)
        }
    }
    @Test fun oneBrokenDraftDoesNotBlockHealthyDraftsAndIsReportedIndividually() = runTest {
        val signer = Signer(owner)
        val payload = ArticleDraftPayload(owner, 1, tags = listOf(listOf("d", "article"), listOf("title", "正常")), content = "本文")
        val healthy = signer.sign(signer.encryptToSelf(articleJson.encodeToString(payload)), 31234,
            listOf(listOf("d", "healthy"), listOf("k", "30023")), 100)
        val broken = signer.sign("encrypted:broken-json", 31234,
            listOf(listOf("d", "broken"), listOf("k", "30023")), 100)
        val repository = ArticleDraftRepository(signer, { setOf(relay) }, { listOf(relay) }, openSession = {
            session(listOf(SubscriptionSignal.Event(relay, healthy, false), SubscriptionSignal.Event(relay, broken, false),
                SubscriptionSignal.FetchCompleted(mapOf(relay to RelayOutcome.Eose), false)))
        }, validate = { true })
        val store = ArticleDraftStore(transport = repository)
        store.refreshDrafts(owner)
        val data = store.read(owner)
        assertEquals("正常", data.drafts.values.single().title)
        assertEquals("broken", data.relayDraftIssues.single().identifier)
        assertEquals(broken.id, data.relayDraftIssues.single().eventId)
    }
    @Test fun wrapsUnsignedArticleAndRestoresItsStructuredEditingFromTheRelay() = runTest {
        val signer = Signer(owner)
        val events = mutableListOf<NostrEvent>()
        val draft = ArticleDraft(ownerPubkey = owner, title = "まとめ", bodyMarkdown = "導入",
            mode = ArticleDraftMode.MatometrStructured,
            references = listOf(MatometrReference("11".repeat(32), commentaryMarkdown = "説明")), revision = 1)
        fun repository() = ArticleDraftRepository(signer, { setOf(relay) }, { listOf(relay) },
            openSession = { spec ->
                assertEquals(listOf("30023"), spec.filters.single().parentKindTags)
                session(events.map { SubscriptionSignal.Event(relay, it, false) } +
                    SubscriptionSignal.FetchCompleted(mapOf(relay to RelayOutcome.Eose), false))
            }, send = { event, targets -> events += event; RelayPublishResult(targets.toSet(), emptyMap()) },
            validate = { true }, nowSeconds = { 100 })
        repository().save(draft)
        val outer = events.single()
        assertEquals(31234, outer.kind)
        assertTrue(outer.content.startsWith("encrypted:"))
        assertTrue(listOf("k", "30023") in outer.tags)
        val unsigned = articleJson.decodeFromString<ArticleDraftPayload>(signer.decrypt(outer.content, owner))
        assertEquals(30023, unsigned.kind)
        assertEquals(draft.content, unsigned.content)
        val restored = repository().load().single()
        assertEquals(draft.references, restored.references)
        assertEquals(draft.bodyMarkdown, restored.bodyMarkdown)
        assertEquals(draft.articleIdentifier, restored.articleIdentifier)
        assertEquals("article-${draft.draftId}", restored.relayDraftIdentifier)
    }
    @Test fun deletedDraftDoesNotReturnWhenAnOlderRelayCopyArrives() = runTest {
        val signer = Signer(owner)
        var now = 100L
        val events = mutableListOf<NostrEvent>()
        val repository = ArticleDraftRepository(signer, { setOf(relay) }, { listOf(relay) },
            openSession = { session(events.reversed().map { SubscriptionSignal.Event(relay, it, false) } +
                SubscriptionSignal.FetchCompleted(mapOf(relay to RelayOutcome.Eose), false)) },
            send = { event, targets -> events += event; RelayPublishResult(targets.toSet(), emptyMap()) },
            validate = { true }, nowSeconds = { now })
        val draft = ArticleDraft(ownerPubkey = owner, title = "下書き")
        repository.save(draft)
        now = 101
        repository.delete(draft)
        assertEquals("", events.last().content)
        assertEquals(events.first().tags, events.last().tags)
        assertTrue(repository.load().isEmpty())
    }
    @Test fun foreignUnsignedArticleCanBeEditedWithoutChangingItsDraftAddress() {
        val signer = Signer(owner)
        val payload = ArticleDraftPayload(owner, 1, tags = listOf(listOf("d", "article-address"), listOf("title", "他クライアント"), listOf("t", "matometr")),
            content = MatometrReference("11".repeat(32)).uri)
        val event = signer.sign(signer.encryptToSelf(articleJson.encodeToString(payload)), 31234,
            listOf(listOf("d", "foreign-draft"), listOf("k", "30023")), 100)
        val restored = decodeArticleDraft(event, signer)!!
        assertEquals("foreign-draft", restored.relayDraftIdentifier)
        assertEquals("article-address", restored.articleIdentifier)
        assertEquals(ArticleDraftMode.MatometrMarkdown, restored.mode)
        assertEquals("他クライアント", restored.title)
        assertNull(decodeArticleDraft(event.copy(pubkey = "bb".repeat(32)), signer))
        assertNull(decodeArticleDraft(event.copy(tags = listOf(listOf("d", "foreign-draft"), listOf("k", "1"))), signer))
    }
    @Test fun relayRejectionAndIncompleteFetchAreFailuresRatherThanSavedOrEmptyDrafts() = runTest {
        val signer = Signer(owner)
        val repository = ArticleDraftRepository(signer, { setOf(relay) }, { listOf(relay) },
            openSession = { session(listOf(SubscriptionSignal.FetchCompleted(mapOf(relay to RelayOutcome.TimedOut), true))) },
            send = { _, _ -> RelayPublishResult(emptySet(), mapOf(relay to "rejected"), setOf(relay)) },
            validate = { true }, nowSeconds = { 100 })
        assertFailsWith<IllegalStateException> { repository.save(ArticleDraft(ownerPubkey = owner, title = "保持")) }
        assertFailsWith<IllegalStateException> { repository.load() }
    }
    @Test fun oneUnreachableRelayKeepsDraftsFromCompletedRelaysAndIsReported() = runTest {
        val signer = Signer(owner)
        val down = "wss://down.example"
        val payload = ArticleDraftPayload(owner, 1, tags = listOf(listOf("d", "article"), listOf("title", "残る")), content = "本文")
        val event = signer.sign(signer.encryptToSelf(articleJson.encodeToString(payload)), 31234,
            listOf(listOf("d", "draft"), listOf("k", "30023")), 100)
        val repository = ArticleDraftRepository(signer, { setOf(relay, down) }, { listOf(relay) },
            openSession = { session(listOf(SubscriptionSignal.Event(relay, event, false),
                SubscriptionSignal.FetchCompleted(mapOf(relay to RelayOutcome.Eose, down to RelayOutcome.TimedOut), true))) },
            validate = { true })
        assertEquals(listOf("残る"), repository.load().map { it.title })
        assertEquals(listOf(down), repository.incompleteRelays)
    }
    @Test fun latestBlankDraftHidesOlderVersionsEvenInANewRepository() = runTest {
        val signer = Signer(owner)
        val draft = ArticleDraft(ownerPubkey = owner, title = "古い版")
        val payload = ArticleDraftPayload(owner, 1, tags = listOf(listOf("d", draft.articleIdentifier)), content = "本文")
        val old = signer.sign(signer.encryptToSelf(articleJson.encodeToString(payload)), 31234,
            listOf(listOf("d", "draft"), listOf("k", "30023")), 100)
        val deleted = signer.sign("", 31234, old.tags, 101)
        val repository = ArticleDraftRepository(signer, { setOf(relay) }, { listOf(relay) },
            openSession = { session(listOf(SubscriptionSignal.Event(relay, deleted, false), SubscriptionSignal.Event(relay, old, false),
                SubscriptionSignal.FetchCompleted(mapOf(relay to RelayOutcome.Eose), false))) },
            validate = { true })
        assertTrue(repository.load().isEmpty())
    }
}
