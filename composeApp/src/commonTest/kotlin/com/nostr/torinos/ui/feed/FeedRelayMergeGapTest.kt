package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * 複数リレーのマージで投稿が抜けないかを、手持ちデータに従って応答する疑似リレーで確かめる。
 *
 * リレー A は投稿がまばらで、30 件で遠くまで遡る。リレー B は投稿が密で、30 件では
 * 少ししか遡らない。両方の和集合を「正解」とし、フィードの表示と突き合わせる。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedRelayMergeGapTest {
    private val newest = Clock.System.now().epochSeconds - 3_600
    private val sparseEvents = (0 until 40).map { event("a-$it", newest - it * 300L) }
    private val denseEvents = (0 until 100).map { event("b-$it", newest - 5 - it * 10L) }
    private val truth = (sparseEvents + denseEvents).sortedByDescending { it.createdAt }

    @Test
    fun loadingToTheEndShowsEveryEventFromEveryRelay() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)

        loadToTheEnd(controller, relays)

        assertNoMissing(controller)
        controller.close()
    }

    @Test
    fun firstPageHasNoHoleAboveItsOldestVisibleEvent() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)

        val shown = controller.state.value.events
        val oldestShown = shown.minOf { it.createdAt }
        val expected = truth.filter { it.createdAt >= oldestShown }.map { it.id }.toSet()
        val missing = expected - shown.map { it.id }.toSet()
        assertEquals(
            emptySet(),
            missing,
            "表示中の最古 (${newest - oldestShown}s 前) より新しいのに表示されていない投稿: ${missing.size} 件",
        )
        controller.close()
    }

    @Test
    fun loadingToTheEndAfterTabSwitchShowsEveryEventFromEveryRelay() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)

        // タブを離れて戻る
        controller.stopSubscriptions()
        settle(relays)
        controller.startSubscriptions()
        settle(relays)

        loadToTheEnd(controller, relays)

        assertNoMissing(controller)
        controller.close()
    }

    @Test
    fun pageInterruptedByTabSwitchIsFetchedAgainAfterResume() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)

        // 次ページの応答前にタブを離れ、取得途中のページを捨てる
        relays.held += setOf("relay-a", "relay-b")
        controller.loadMore()
        runCurrent()
        controller.stopSubscriptions()
        runCurrent()
        relays.held.clear()
        controller.startSubscriptions()
        settle(relays)

        loadToTheEnd(controller, relays)

        assertNoMissing(controller)
        controller.close()
    }

    @Test
    fun lateRelayCatchesUpToTheRevealedRangeWithoutLoadMore() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        relays.held += "relay-b"
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        // relay-b が応答しないまま猶予が過ぎ、relay-a の範囲で表示が進む
        settle(relays)
        val oldestShown = controller.state.value.events.minOf { it.createdAt }

        relays.held.clear()
        settle(relays)

        val shownIds = controller.state.value.events.map { it.id }.toSet()
        val missing = truth.filter { it.createdAt >= oldestShown && it.id !in shownIds }
        assertEquals(emptyList(), missing.map { it.id }, "遅れたリレーが表示済みの範囲を埋めていない")
        controller.close()
    }

    @Test
    fun unresponsiveRelayAfterResumeDoesNotStallLoadMore() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)

        // relay-b が応答しなくなった状態でタブを切り替え、戻ってから続きを読む
        relays.held += "relay-b"
        controller.loadMore()
        settle(relays)
        controller.stopSubscriptions()
        runCurrent()
        controller.startSubscriptions()
        settle(relays)
        val shownBefore = controller.state.value.events.size

        controller.loadMore()
        settle(relays)

        assertTrue(
            controller.state.value.events.size > shownBefore,
            "応答しないリレーに境界が止められ、続きが表示されない",
        )
        controller.close()
    }

    @Test
    fun relayAddedLaterDoesNotBlockLoadMore() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)
        repeat(3) {
            controller.loadMore()
            settle(relays)
        }
        val shownBefore = controller.state.value.events.size

        // 追加されたリレーは最新から取り直すが、応答が遅くても既存リレーの続きは止めない
        relays.held += "relay-c"
        relays.add("relay-c", emptyList())
        runCurrent()
        controller.loadMore()
        runCurrent()
        relays.serveFeedFetches()
        runCurrent()

        assertTrue(
            controller.state.value.events.size > shownBefore,
            "後から追加したリレーに境界が止められ、続きが表示されない",
        )
        controller.close()
    }

    @Test
    fun postsWhileAwayBeyondOnePageAreAllShownAfterResume() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)

        controller.stopSubscriptions()
        runCurrent()
        val whileAway = postsWhileAway()
        relays.append("relay-b", whileAway)
        controller.startSubscriptions()
        settle(relays)

        assertAllShown(controller, whileAway)
        controller.close()
    }

    @Test
    fun manualRefreshShowsEveryPostSinceTheNewestShown() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)
        repeat(2) {
            controller.loadMore()
            settle(relays)
        }

        val whileAway = postsWhileAway()
        relays.append("relay-b", whileAway)
        controller.refresh()
        settle(relays)

        assertAllShown(controller, whileAway)
        // 更新前に読み込んでいた範囲の続きも、抜けなく読める
        loadToTheEnd(controller, relays)
        assertNoMissing(controller)
        controller.close()
    }

    @Test
    fun resumeSyncInterruptedByAnotherTabSwitchIsCompletedLater() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)

        controller.stopSubscriptions()
        runCurrent()
        val whileAway = postsWhileAway()
        relays.append("relay-b", whileAway)
        // 復帰直後の同期が 1 ページ返ったところで、またタブを離れる
        controller.startSubscriptions()
        runCurrent()
        relays.serveFeedFetches()
        runCurrent()
        relays.held += "relay-b"
        relays.serveFeedFetches()
        runCurrent()
        controller.stopSubscriptions()
        runCurrent()
        relays.held.clear()
        controller.startSubscriptions()
        settle(relays)

        assertAllShown(controller, whileAway)
        controller.close()
    }

    @Test
    fun refreshIndicatorStaysUntilTheSyncCompletes() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)

        relays.held += setOf("relay-a", "relay-b")
        controller.refresh()
        runCurrent()
        assertTrue(controller.state.value.isRefreshing, "差分取得の完了前に更新表示が消えた")

        relays.held.clear()
        settle(relays)
        assertFalse(controller.state.value.isRefreshing)
        controller.close()
    }

    @Test
    fun repeatedTabSwitchesDuringSyncLeaveNoHole() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)

        controller.stopSubscriptions()
        runCurrent()
        val whileAway = postsWhileAway()
        relays.append("relay-b", whileAway)
        // 差分取得が 1 ページ進むたびにタブを離れて戻る
        repeat(5) {
            controller.startSubscriptions()
            runCurrent()
            relays.serveFeedFetches()
            runCurrent()
            relays.held += "relay-b"
            relays.serveFeedFetches()
            runCurrent()
            controller.stopSubscriptions()
            runCurrent()
            relays.held.clear()
            advanceTimeBy(10_000)
        }
        controller.startSubscriptions()
        settle(relays)

        assertAllShown(controller, whileAway)
        controller.close()
    }

    @Test
    fun futureDatedPostDoesNotSuppressResumeSync() = runTest {
        val relays = SimulatedRelays(mapOf("relay-a" to sparseEvents, "relay-b" to denseEvents))
        val controller = FeedController(computeDispatcher = Dispatchers.Unconfined, scope = backgroundScope, subscriptions = relays)
        settle(relays)
        // 時計のずれた投稿がライブで届く
        relays.liveSessions().forEach { session ->
            session.emit(
                SubscriptionSignal.Event(
                    "relay-a",
                    event("future", Clock.System.now().epochSeconds + 86_400),
                    isLive = true,
                ),
            )
        }
        settle(relays)

        controller.stopSubscriptions()
        runCurrent()
        val whileAway = postsWhileAway()
        relays.append("relay-b", whileAway)
        controller.startSubscriptions()
        settle(relays)

        assertAllShown(controller, whileAway)
        controller.close()
    }

    /** 最後に表示した投稿より新しく、1 ページ (30 件) を超える投稿。 */
    private fun postsWhileAway(): List<NostrEvent> {
        val now = Clock.System.now().epochSeconds
        return (0 until 80).map { event("away-$it", now - 10 - it * 20L) }
    }

    private fun assertAllShown(controller: FeedController, expected: List<NostrEvent>) {
        val shownIds = controller.state.value.events.map { it.id }.toSet()
        val missing = expected.filter { it.id !in shownIds }
        assertEquals(
            emptyList(),
            missing.map { it.id },
            "離れていた間の投稿のうち表示されていないもの: ${missing.size} 件 / ${expected.size} 件",
        )
    }

    private fun TestScope.settle(relays: SimulatedRelays) {
        repeat(5) {
            runCurrent()
            relays.serveFeedFetches()
            runCurrent()
            advanceTimeBy(3_000)
            runCurrent()
        }
    }

    private fun TestScope.loadToTheEnd(controller: FeedController, relays: SimulatedRelays) {
        repeat(50) {
            if (!controller.state.value.canLoadMore) return
            controller.loadMore()
            settle(relays)
        }
    }

    private fun assertNoMissing(controller: FeedController) {
        val shownIds = controller.state.value.events.map { it.id }.toSet()
        val missing = truth.filter { it.id !in shownIds }
        assertEquals(
            emptyList(),
            missing.map { "${it.id}(${newest - it.createdAt}s前)" },
            "表示されていない投稿: ${missing.size} 件 / 全 ${truth.size} 件",
        )
    }

    /** フィード取得の REQ に、各リレーの手持ちデータから newest-first で応答する。 */
    private class SimulatedRelays(initialStores: Map<String, List<NostrEvent>>) : FeedSubscriptionGateway {
        private val stores = initialStores.toMutableMap()
        private val enabledUrls = MutableStateFlow(stores.keys.toSet())
        private val sessions = mutableListOf<Session>()
        private val served = mutableSetOf<Session>()
        /** 応答を保留するリレー。保留中の REQ は解除後に応答する。 */
        val held = mutableSetOf<String>()

        override val readableRelayUrls: Flow<Set<String>> = enabledUrls

        fun append(url: String, events: List<NostrEvent>) {
            stores[url] = stores.getValue(url) + events
        }

        fun add(url: String, events: List<NostrEvent>) {
            stores[url] = events
            enabledUrls.value = stores.keys.toSet()
        }

        override fun events(subscriptionId: String): Flow<NostrEvent> = emptyFlow()

        override suspend fun targetRelayUrls(target: RelayTarget): Set<String> = urlsOf(target)

        private fun urlsOf(target: RelayTarget): Set<String> = when (target) {
            RelayTarget.AllEnabled -> stores.keys.toSet()
            is RelayTarget.Single -> setOf(target.url).intersect(stores.keys)
            is RelayTarget.Explicit -> target.urls.intersect(stores.keys)
        }

        fun liveSessions(): List<Session> =
            sessions.filter { it.spec.behavior is SubscriptionBehavior.Live && !it.closed && it.spec.id.startsWith("feed-") }

        override suspend fun open(spec: SubscriptionSpec): SubscriptionSession =
            Session(spec).also(sessions::add)

        override suspend fun subscribe(subscriptionId: String, filter: NostrFilter, target: RelayTarget): Set<String> =
            emptySet()

        override fun close(subscriptionId: String) = Unit

        fun serveFeedFetches() {
            sessions
                .filter { it.spec.behavior is SubscriptionBehavior.Fetch && it !in served && !it.closed }
                .filter { session -> session.spec.filters.all { it.isFeedFilter() } }
                .filter { session -> urlsOf(session.spec.target).none { it in held } }
                .forEach { session ->
                    served += session
                    val urls = urlsOf(session.spec.target)
                    urls.forEach { url ->
                        query(stores.getValue(url), session.spec.filters).forEach { event ->
                            session.emit(SubscriptionSignal.Event(url, event, isLive = false))
                        }
                        session.emit(SubscriptionSignal.Eose(url))
                    }
                    session.emit(
                        SubscriptionSignal.FetchCompleted(
                            outcomes = urls.associateWith { RelayOutcome.Eose },
                            timedOut = false,
                        ),
                    )
                }
        }

        private fun query(store: List<NostrEvent>, filters: List<NostrFilter>): List<NostrEvent> =
            filters.flatMap { filter ->
                store
                    .filter { event ->
                        (filter.kinds?.contains(event.kind) ?: true) &&
                            (filter.since?.let { event.createdAt >= it } ?: true) &&
                            (filter.until?.let { event.createdAt <= it } ?: true)
                    }
                    .sortedByDescending { it.createdAt }
                    .take(filter.limit ?: RELAY_DEFAULT_LIMIT)
            }.distinctBy { it.id }

        private fun NostrFilter.isFeedFilter(): Boolean =
            ids == null && eTags == null && qTags == null && rootEventTags == null && kinds?.contains(1) == true
    }

    private class Session(val spec: SubscriptionSpec) : SubscriptionSession {
        private val channel = Channel<SubscriptionSignal>(Channel.UNLIMITED)
        override val id: String = spec.id
        override val signals: Flow<SubscriptionSignal> = channel.receiveAsFlow()
        var closed = false

        fun emit(signal: SubscriptionSignal) {
            channel.trySend(signal)
        }

        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit

        override suspend fun close() {
            closed = true
        }
    }

    private fun event(id: String, createdAt: Long) = NostrEvent(
        id = id,
        pubkey = "author",
        createdAt = createdAt,
        kind = 1,
        tags = emptyList(),
        content = id,
        sig = "signature",
    )

    private companion object {
        const val RELAY_DEFAULT_LIMIT = 500
    }
}
