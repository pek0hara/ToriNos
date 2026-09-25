package com.nostr.torinos.ui.status

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.AccountSessionResources
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.status.GENERAL_STATUS_IDENTIFIER
import com.nostr.torinos.status.STATUS_EVENT_KIND
import com.nostr.torinos.status.StatusPublishState
import com.nostr.torinos.status.StatusPublisher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
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

        val ownSubscription = gateway.subscribed.single { it.filter.authors == listOf(FakeSigner.pubkey) }
        assertEquals(null, ownSubscription.filter.dTags)
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
        val filter: NostrFilter,
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

        override suspend fun subscribe(subscriptionId: String, filter: NostrFilter, relayUrl: String) {
            subscribed += Subscription(subscriptionId, filter, relayUrl)
        }

        override fun close(subscriptionId: String) {
            closed += subscriptionId
        }

        fun emit(subscriptionId: String, event: NostrEvent) {
            checkNotNull(eventFlows[subscriptionId]).tryEmit(event)
        }
    }

    private object FakeProfileGateway : StatusProfileGateway {
        override fun observeChanges(): Flow<Set<String>> = emptyFlow()
        override fun getCached(pubkeys: Set<String>): Map<String, NostrProfile> = emptyMap()
        override suspend fun ensureProfiles(pubkeys: Set<String>, relayHint: String) = Unit
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
        ) = NostrEvent(
            id = id.repeat(64),
            pubkey = "2".repeat(64),
            createdAt = 1000L,
            kind = STATUS_EVENT_KIND,
            tags = tags,
            content = content,
            sig = "3".repeat(128),
        )
    }
}
