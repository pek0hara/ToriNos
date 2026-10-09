package com.nostr.torinos.ui.status

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.AccountSessionResources
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.status.GENERAL_STATUS_IDENTIFIER
import com.nostr.torinos.status.MUSIC_STATUS_IDENTIFIER
import com.nostr.torinos.status.STATUS_EVENT_KIND
import com.nostr.torinos.status.StatusPublishState
import com.nostr.torinos.status.StatusPublisher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {
    @Test
    fun startIsIdempotentAndRelaySwitchClosesOldSubscriptions() = runTest {
        val gateway = FakeSubscriptionGateway()
        val viewModel = viewModel(gateway)

        viewModel.start(RELAY_A)
        runCurrent()
        val relayAIds = gateway.subscribed.map { it.subscriptionId }.toSet()
        assertEquals(1, relayAIds.size)

        viewModel.start(RELAY_A)
        runCurrent()
        assertEquals(1, gateway.subscribed.size)

        viewModel.start(RELAY_B)
        runCurrent()
        assertTrue(gateway.closed.containsAll(relayAIds))
        assertEquals(2, gateway.subscribed.size)

        viewModel.stop()
        assertTrue(gateway.closed.contains(gateway.subscribed.last().subscriptionId))
    }

    @Test
    fun statusQueryPrefersDisplayedCategoriesAndKeepsSomeOthers() = runTest {
        val gateway = FakeSubscriptionGateway()
        val viewModel = viewModel(gateway)

        viewModel.start(RELAY_A)
        runCurrent()

        val filters = gateway.subscribed.single().filters
        assertEquals(2, filters.size)
        assertEquals(listOf(GENERAL_STATUS_IDENTIFIER, MUSIC_STATUS_IDENTIFIER), filters[0].dTags)
        assertEquals(200, filters[0].limit)
        assertEquals(null, filters[1].dTags)
        assertEquals(50, filters[1].limit)
        assertTrue(filters.all { it.kinds == listOf(STATUS_EVENT_KIND) && it.since == null })
        viewModel.stop()
    }

    @Test
    fun returningToTheSameRelayOnlyAsksForNewerStatuses() = runTest {
        val gateway = FakeSubscriptionGateway()
        val viewModel = viewModel(gateway)
        viewModel.start(RELAY_A)
        runCurrent()
        val first = gateway.subscribed.single().subscriptionId
        gateway.emit(first, event(id = "a", content = "old", createdAt = 300L, pubkey = "4".repeat(64)))
        gateway.emit(first, event(id = "b", content = "new", createdAt = 500L, pubkey = "5".repeat(64)))
        runCurrent()
        viewModel.stop()

        viewModel.start(RELAY_A)
        runCurrent()
        assertEquals(listOf(500L, 500L), gateway.subscribed.last().filters.map { it.since })
        assertEquals(listOf("new", "old"), viewModel.state.value.statuses.map { it.content })
        viewModel.stop()

        viewModel.start(RELAY_B)
        runCurrent()
        assertTrue(gateway.subscribed.last().filters.all { it.since == null })
        viewModel.stop()
    }

    @Test
    fun firstRevealWaitsForEoseAndProfiles() = runTest {
        val gateway = FakeSubscriptionGateway()
        val profiles = SlowProfileGateway()
        val viewModel = StatusViewModel(
            subscriptions = gateway,
            profileGateway = profiles,
            publisher = StatusPublisher(FakeSigner, customEmojis = { emptyList() }) { _, _ -> },
            nowEpochSeconds = { 1000L },
        )
        viewModel.start(RELAY_A)
        runCurrent()
        val id = gateway.subscribed.single().subscriptionId
        val author = "4".repeat(64)
        gateway.emit(id, event(id = "a", content = "hello", pubkey = author))
        runCurrent()
        // 届いただけでは出さない（名前が16進のまま出て、後から差し替わるのを避ける）。
        assertEquals(StatusLoadState.Loading, viewModel.state.value.loadState)

        gateway.emitEose(id)
        runCurrent()
        assertEquals(StatusLoadState.Loading, viewModel.state.value.loadState)
        assertEquals(setOf(author), profiles.requested.single())

        profiles.finish(author)
        runCurrent()
        assertEquals(StatusLoadState.Ready, viewModel.state.value.loadState)
        assertEquals("name", viewModel.state.value.profiles[author]?.name)
        viewModel.stop()
    }

    @Test
    fun slowProfilesDoNotHoldTheListForever() = runTest {
        // 待ちの上限を仮想時間で進めるため、ViewModel の Main をテストのスケジューラにする。
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            slowProfilesScenario()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.slowProfilesScenario() {
        val gateway = FakeSubscriptionGateway()
        val profiles = SlowProfileGateway()
        val viewModel = StatusViewModel(
            subscriptions = gateway,
            profileGateway = profiles,
            publisher = StatusPublisher(FakeSigner, customEmojis = { emptyList() }) { _, _ -> },
            nowEpochSeconds = { 1000L },
        )
        viewModel.start(RELAY_A)
        runCurrent()
        val id = gateway.subscribed.single().subscriptionId
        gateway.emit(id, event(id = "a", content = "hello", pubkey = "4".repeat(64)))
        gateway.emitEose(id)
        runCurrent()

        testScheduler.advanceTimeBy(1_001)
        runCurrent()
        assertEquals(StatusLoadState.Ready, viewModel.state.value.loadState)
        viewModel.stop()
    }

    @Test
    fun eventsFromStoppedGenerationAreIgnored() = runTest {
        val gateway = FakeSubscriptionGateway()
        val viewModel = viewModel(gateway)

        viewModel.start(RELAY_A)
        runCurrent()
        val oldId = gateway.subscribed.single().subscriptionId
        viewModel.start(RELAY_B)
        runCurrent()
        val currentId = gateway.subscribed.last().subscriptionId

        gateway.emit(oldId, event(id = "a", content = "old relay"))
        gateway.emit(currentId, event(id = "b", content = "current relay"))
        runCurrent()

        assertEquals(listOf("current relay"), viewModel.state.value.statuses.map { it.content })
        viewModel.stop()
    }

    @Test
    fun failedPublishDoesNotModifyStatuses() = runTest {
        val gateway = FakeSubscriptionGateway()
        val publisher = StatusPublisher(FakeSigner, customEmojis = { emptyList() }) { _, _ ->
            error("offline")
        }
        val viewModel = viewModel(gateway, publisher)
        viewModel.start(RELAY_A)
        runCurrent()

        viewModel.publishStatus(GENERAL_STATUS_IDENTIFIER, "new", null, null)
        runCurrent()

        assertTrue(viewModel.state.value.statuses.isEmpty())
        assertIs<StatusPublishState.Failed>(viewModel.state.value.publishState)
        viewModel.stop()
    }

    @Test
    fun successfulPublishIsAppliedOnceAndCanBeConsumed() = runTest {
        val gateway = FakeSubscriptionGateway()
        val publisher = StatusPublisher(FakeSigner, customEmojis = { emptyList() }) { _, _ -> }
        val viewModel = viewModel(gateway, publisher)
        viewModel.start(RELAY_A)
        runCurrent()

        viewModel.publishStatus(GENERAL_STATUS_IDENTIFIER, "new", null, null)
        runCurrent()

        assertEquals(listOf("new"), viewModel.state.value.statuses.map { it.content })
        val succeeded = assertIs<StatusPublishState.Succeeded>(viewModel.state.value.publishState)
        viewModel.consumePublishResult(succeeded.eventId)
        assertIs<StatusPublishState.Idle>(viewModel.state.value.publishState)
        viewModel.stop()
    }

    @Test
    fun ownStatusSubscriptionLoadsEveryCategoryForEditing() = runTest {
        val gateway = FakeSubscriptionGateway()
        val session = AccountSession(
            sessionId = "test",
            pubkey = FakeSigner.pubkey,
            signer = FakeSigner,
            resources = AccountSessionResources(),
        )
        val viewModel = StatusViewModel(
            accountSession = session,
            subscriptions = gateway,
            profileGateway = FakeProfileGateway,
            publisher = StatusPublisher(FakeSigner, customEmojis = { emptyList() }) { _, _ -> },
            nowEpochSeconds = { 1000L },
        )

        viewModel.start(RELAY_A)
        runCurrent()

        val ownSubscription = gateway.subscribed.single { sub -> sub.filters.any { it.authors == listOf(FakeSigner.pubkey) } }
        assertEquals(null, ownSubscription.filters.single().dTags)
        gateway.emit(
            ownSubscription.subscriptionId,
            event(
                id = "a",
                content = "listening",
                tags = listOf(listOf("d", "music")),
            ),
        )
        runCurrent()

        assertEquals("listening", viewModel.state.value.ownStatuses["music"]?.content)
        viewModel.stop()
        session.resources.close()
    }

    @Test
    fun deletingStatusPublishesTombstoneAndRemovesSelectedCategory() = runTest {
        val gateway = FakeSubscriptionGateway()
        val publisher = StatusPublisher(FakeSigner, customEmojis = { emptyList() }) { _, _ -> }
        val viewModel = viewModel(gateway, publisher)
        viewModel.start(RELAY_A)
        runCurrent()
        val subscriptionId = gateway.subscribed.single().subscriptionId
        gateway.emit(subscriptionId, event(id = "f", content = "old"))
        runCurrent()

        viewModel.deleteStatus(GENERAL_STATUS_IDENTIFIER)
        runCurrent()

        assertTrue(viewModel.state.value.statuses.isEmpty())
        assertIs<StatusPublishState.Succeeded>(viewModel.state.value.publishState)
        viewModel.stop()
    }

    @Test
    fun selectedCustomCategoryRemainsAvailableUntilDeselected() = runTest {
        val gateway = FakeSubscriptionGateway()
        val viewModel = viewModel(gateway)
        viewModel.start(RELAY_A)
        runCurrent()
        val subscriptionId = gateway.subscribed.single().subscriptionId

        gateway.emit(
            subscriptionId,
            event(
                id = "b",
                content = "coding",
                tags = listOf(listOf("d", "coding")),
            ),
        )
        runCurrent()
        assertTrue("coding" in viewModel.state.value.availableCategories)

        viewModel.toggleCategory("coding")
        gateway.emit(
            subscriptionId,
            event(
                id = "a",
                content = "",
                tags = listOf(listOf("d", "coding")),
            ),
        )
        runCurrent()

        assertTrue("coding" in viewModel.state.value.selectedCategories)
        assertTrue("coding" in viewModel.state.value.availableCategories)

        viewModel.toggleCategory("coding")

        assertFalse("coding" in viewModel.state.value.selectedCategories)
        assertFalse("coding" in viewModel.state.value.availableCategories)
        viewModel.stop()
    }

    private fun viewModel(
        gateway: FakeSubscriptionGateway,
        publisher: StatusPublisher = StatusPublisher(FakeSigner, customEmojis = { emptyList() }) { _, _ -> },
    ) = StatusViewModel(
        subscriptions = gateway,
        profileGateway = FakeProfileGateway,
        publisher = publisher,
        nowEpochSeconds = { 1000L },
    )

    private data class Subscription(
        val subscriptionId: String,
        val filters: List<NostrFilter>,
        val relayUrl: String,
    )

    private class FakeSubscriptionGateway : StatusSubscriptionGateway {
        private val eventFlows = mutableMapOf<String, MutableSharedFlow<NostrEvent>>()
        private val eoseFlows = mutableMapOf<String, MutableSharedFlow<Unit>>()
        val subscribed = mutableListOf<Subscription>()
        val closed = mutableListOf<String>()

        override fun events(subscriptionId: String): Flow<NostrEvent> =
            eventFlows.getOrPut(subscriptionId) { MutableSharedFlow(extraBufferCapacity = 16) }

        override fun eose(subscriptionId: String): Flow<Unit> =
            eoseFlows.getOrPut(subscriptionId) { MutableSharedFlow(extraBufferCapacity = 1) }

        override suspend fun subscribe(subscriptionId: String, filters: List<NostrFilter>, relayUrl: String) {
            subscribed += Subscription(subscriptionId, filters, relayUrl)
        }

        override fun close(subscriptionId: String) {
            closed += subscriptionId
        }

        fun emit(subscriptionId: String, event: NostrEvent) {
            checkNotNull(eventFlows[subscriptionId]).tryEmit(event)
        }

        fun emitEose(subscriptionId: String) {
            checkNotNull(eoseFlows[subscriptionId]).tryEmit(Unit)
        }
    }

    /** awaitProfiles が [finish] まで終わらないプロフィール取得。 */
    private class SlowProfileGateway : StatusProfileGateway {
        val requested = mutableListOf<Set<String>>()
        private val cached = mutableMapOf<String, NostrProfile>()
        private val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        override fun observeChanges(): Flow<Set<String>> = emptyFlow()
        override fun getCached(pubkeys: Set<String>): Map<String, NostrProfile> = cached.filterKeys { it in pubkeys }
        override suspend fun awaitProfiles(pubkeys: Set<String>, relayHint: String) {
            requested += pubkeys
            done.await()
        }

        fun finish(pubkey: String) {
            cached[pubkey] = NostrProfile(name = "name")
            done.complete(Unit)
        }
    }

    private object FakeProfileGateway : StatusProfileGateway {
        override fun observeChanges(): Flow<Set<String>> = emptyFlow()
        override fun getCached(pubkeys: Set<String>): Map<String, NostrProfile> = emptyMap()
        override suspend fun awaitProfiles(pubkeys: Set<String>, relayHint: String) = Unit
    }

    private object FakeSigner : AccountSigner {
        override val pubkey: String = "2".repeat(64)
        override fun encryptToSelf(plaintext: String): String = plaintext
        override fun decrypt(content: String, peerPubkey: String): String = content

        override fun sign(
            content: String,
            kind: Int,
            tags: List<List<String>>,
            createdAt: Long?,
        ) = event(id = "1", content = content, tags = tags)
    }

    companion object {
        private const val RELAY_A = "wss://relay-a.example"
        private const val RELAY_B = "wss://relay-b.example"

        private fun event(
            id: String,
            content: String,
            tags: List<List<String>> = listOf(listOf("d", GENERAL_STATUS_IDENTIFIER)),
            createdAt: Long = 1000L,
            pubkey: String = "2".repeat(64),
        ) = NostrEvent(
            id = id.repeat(64),
            pubkey = pubkey,
            createdAt = createdAt,
            kind = STATUS_EVENT_KIND,
            tags = tags,
            content = content,
            sig = "3".repeat(128),
        )
    }
}
