package com.nostr.torinos.emoji

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class EmojiPreferenceSyncTest {
    private val pubkey = "f".repeat(64)
    private val author = "a".repeat(64)
    private val cat = CustomEmoji("cat", "https://example.com/cat.png")
    private val dog = CustomEmoji("dog", "https://example.com/dog.png")
    private val catsAddress = EmojiSetAddress(author, "cats")
    private val catsSet = RegisteredEmojiSet(catsAddress, "Cats", listOf(cat))
    private val missingAddress = EmojiSetAddress(author, "missing")

    @Test
    fun parsesPreferredEmojisAndEmojiSetPointers() {
        val result = parseEmojiPreferenceTags(
            listOf(
                listOf("emoji", "blobcat", "https://example.com/blobcat.png"),
                listOf("a", catsAddress.value),
                listOf("a", "30023:${"b".repeat(64)}:article"),
                listOf("emoji", "", "https://example.com/invalid.png"),
            ),
        )

        assertEquals(listOf(CustomEmoji("blobcat", "https://example.com/blobcat.png")), result.emojis)
        assertEquals(listOf(catsAddress), result.setReferences)
    }

    @Test
    fun replacesEmojiPreferencesWhilePreservingUnknownTags() {
        val tags = buildEmojiPreferenceTags(
            previousTags = listOf(
                listOf("client", "another-client"),
                listOf("emoji", "old", "https://example.com/old.png"),
                listOf("a", "30030:${"d".repeat(64)}:old"),
            ),
            emojis = listOf(CustomEmoji(":new:", "https://example.com/new.png")),
            setReferences = listOf(catsAddress),
        )

        assertEquals(
            listOf(
                listOf("client", "another-client"),
                listOf("emoji", "new", "https://example.com/new.png"),
                listOf("a", catsAddress.value),
            ),
            tags,
        )
    }

    @Test
    fun localChangeIsPublishedWithoutRewritingLocalState() = runTest {
        val env = environment()
        env.repository.toggleFavorite(cat)
        env.settle(1_000)

        val published = assertNotNull(env.transport.published.lastOrNull())
        assertEquals(listOf(listOf("emoji", "cat", cat.imageUrl)), published.tags)
        assertEquals(listOf(cat), env.repository.preferences.value.favorites)
        assertFalse(env.repository.preferences.value.hasUnsyncedChanges)
    }

    @Test
    fun changeMadeWhilePublishingIsKeptAndSentNext() = runTest {
        val env = environment()
        val gate = CompletableDeferred<Unit>()
        env.transport.publishGate = gate
        env.repository.toggleFavorite(cat)
        env.settle(1_000)
        assertEquals(1, env.transport.attempts)

        env.repository.toggleFavorite(dog)
        env.settle(1_000)
        gate.complete(Unit)
        env.transport.publishGate = null
        env.settle(1_000)

        assertEquals(listOf(cat, dog), env.repository.preferences.value.favorites)
        assertEquals(
            listOf(listOf("emoji", "cat", cat.imageUrl), listOf("emoji", "dog", dog.imageUrl)),
            env.transport.published.last().tags,
        )
        assertFalse(env.repository.preferences.value.hasUnsyncedChanges)
    }

    @Test
    fun remoteIsAppliedAndUnresolvedPointersArePublishedAgain() = runTest {
        val env = environment()
        env.transport.remote = preferencesEvent(
            createdAt = 10,
            tags = listOf(listOf("a", catsAddress.value), listOf("a", missingAddress.value)),
        )
        env.transport.sets = mapOf(catsAddress to catsSet)
        env.sync.refresh()
        env.settle()

        val applied = env.repository.preferences.value
        assertEquals(listOf(catsAddress), applied.sets.map { it.address })
        assertEquals(setOf(missingAddress), applied.unresolvedSetAddresses)

        env.repository.toggleFavorite(dog)
        env.settle(1_000)

        val tags = env.transport.published.last().tags
        assertTrue(listOf("a", missingAddress.value) in tags)
        assertTrue(listOf("a", catsAddress.value) in tags)
    }

    @Test
    fun remoteDoesNotOverwriteUnsentLocalChanges() = runTest {
        val env = environment()
        env.transport.failPublish = true
        env.repository.toggleFavorite(cat)
        env.settle(1_000)
        assertTrue(env.repository.preferences.value.hasUnsyncedChanges)

        env.transport.remote = preferencesEvent(createdAt = 10, tags = listOf(listOf("emoji", "dog", dog.imageUrl)))
        env.transport.failPublish = false
        env.sync.refresh()
        env.settle(1_000)

        assertEquals(listOf(cat), env.repository.preferences.value.favorites)
        assertEquals(listOf(listOf("emoji", "cat", cat.imageUrl)), env.transport.published.last().tags)
    }

    @Test
    fun failedRelaysStayInOutboxAndAreRetried() = runTest {
        val env = environment()
        env.transport.acceptOnly = setOf("wss://a.example")
        env.repository.toggleFavorite(cat)
        env.settle(1_000)

        val pending = assertNotNull(env.outbox.get(pubkey))
        assertEquals(setOf("wss://b.example"), pending.pendingRelayUrls.map { it.trimEnd('/') }.toSet())

        env.transport.acceptOnly = null
        env.settle(EmojiPreferenceSync.RETRY_INTERVAL_MS + 1_000)

        assertNull(env.outbox.get(pubkey))
    }

    private class Environment(
        val scope: TestScope,
        val repository: CustomEmojiRepository,
        val sync: EmojiPreferenceSync,
        val transport: FakeTransport,
        val outbox: EmojiPreferenceOutbox,
    ) {
        fun settle(millis: Long = 0) {
            scope.testScheduler.runCurrent()
            if (millis > 0) scope.testScheduler.advanceTimeBy(millis)
            scope.testScheduler.runCurrent()
        }
    }

    private fun TestScope.environment(): Environment {
        val store = FakeStore()
        val repository = CustomEmojiRepository(pubkey, backgroundScope, EmojiPreferencesStorage(store))
        val transport = FakeTransport()
        val outbox = EmojiPreferenceOutbox(store)
        val sync = EmojiPreferenceSync(
            pubkey = pubkey,
            signer = FakeSigner(pubkey) { testScheduler.currentTime },
            repository = repository,
            scope = backgroundScope,
            transport = transport,
            ensureActive = {},
            outbox = outbox,
            now = { 1_000_000L + testScheduler.currentTime },
        )
        repository.start()
        sync.start()
        return Environment(this, repository, sync, transport, outbox).also { it.settle() }
    }

    private fun preferencesEvent(createdAt: Long, tags: List<List<String>>) = NostrEvent(
        id = "remote-$createdAt",
        pubkey = pubkey,
        createdAt = createdAt,
        kind = EmojiPreferenceSync.KIND_EMOJI_PREFERENCES,
        tags = tags,
        content = "",
        sig = "sig",
    )

    private class FakeTransport : EmojiPreferenceTransport {
        var remote: NostrEvent? = null
        var sets: Map<EmojiSetAddress, RegisteredEmojiSet> = emptyMap()
        var publishGate: CompletableDeferred<Unit>? = null
        var failPublish = false
        var acceptOnly: Set<String>? = null
        var attempts = 0
        val published = mutableListOf<NostrEvent>()

        override suspend fun awaitRelaysLoaded() = Unit
        override suspend fun fetchLatestPreferences(pubkey: String): NostrEvent? = remote
        override suspend fun fetchSets(addresses: List<EmojiSetAddress>) = sets.filterKeys { it in addresses }
        override fun writableRelays(): List<String> = listOf("wss://a.example", "wss://b.example")

        override suspend fun publish(event: NostrEvent, relayUrls: Collection<String>): Set<String> {
            attempts += 1
            publishGate?.await()
            if (failPublish) return emptySet()
            published += event
            val accepted = acceptOnly
            return if (accepted == null) relayUrls.toSet() else relayUrls.filter { it in accepted }.toSet()
        }
    }

    private class FakeSigner(override val pubkey: String, private val clock: () -> Long) : AccountSigner {
        private var counter = 0
        override fun encryptToSelf(plaintext: String) = plaintext
        override fun decrypt(content: String, peerPubkey: String) = content
        override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?) =
            NostrEvent("signed-${counter++}", pubkey, createdAt ?: clock(), kind, tags, content, "sig")
    }

    private class FakeStore : EmojiKeyValueStore {
        val values = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
