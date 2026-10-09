package com.nostr.torinos.ui.search

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

internal const val TEST_SEARCH_RELAY = "wss://search.test"

internal class FakeSearchSession(val spec: SubscriptionSpec, private val onClose: (FakeSearchSession) -> Unit) :
    SubscriptionSession {
    override val id: String = spec.id
    private val channel = Channel<SubscriptionSignal>(Channel.UNLIMITED)
    override val signals: Flow<SubscriptionSignal> = channel.receiveAsFlow()
    var closeCount = 0
        private set
    val isClosed: Boolean get() = closeCount > 0
    val filter: NostrFilter get() = spec.filters.first()

    override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = error("finite")

    override suspend fun close() {
        closeCount++
        channel.close()
        onClose(this)
    }

    fun event(event: NostrEvent) {
        channel.trySend(SubscriptionSignal.Event(TEST_SEARCH_RELAY, event, isLive = false))
    }

    fun events(events: List<NostrEvent>) = events.forEach(::event)

    fun complete(outcome: RelayOutcome = RelayOutcome.Eose) {
        channel.trySend(
            SubscriptionSignal.FetchCompleted(
                outcomes = mapOf(TEST_SEARCH_RELAY to outcome),
                timedOut = outcome == RelayOutcome.TimedOut,
            ),
        )
    }
}

internal class FakeSearchGateway : SearchGateway {
    val sessions = mutableListOf<FakeSearchSession>()
    private val openIds = mutableSetOf<String>()
    val pageSessions: List<FakeSearchSession> get() = sessions.filter { it.filter.search != null }
    val engagementSessions: List<FakeSearchSession> get() = sessions.filter { it.filter.search == null }
    val openSessions: List<FakeSearchSession> get() = sessions.filterNot { it.isClosed }
    var failNextOpen: Throwable? = null

    val profileCache = mutableMapOf<String, NostrProfile>()
    private val profileChangeFlow = MutableSharedFlow<Set<String>>(extraBufferCapacity = 64)
    var activeProfileWatchers = 0
        private set
    val requestedProfiles = mutableListOf<Set<String>>()

    val userSearches = mutableListOf<String>()
    var userSearchResult = CompletableDeferred<List<Pair<String, NostrProfile>>>()

    override suspend fun openSubscription(spec: SubscriptionSpec): SubscriptionSession {
        failNextOpen?.let {
            failNextOpen = null
            throw it
        }
        // 実際のRepositoryと同じく、使用中の購読IDは再利用できない。
        check(openIds.add(spec.id)) { "購読IDはすでに使用されています: ${spec.id}" }
        return FakeSearchSession(spec) { openIds.remove(it.id) }.also(sessions::add)
    }

    override fun profileChanges(): Flow<Set<String>> = flow {
        activeProfileWatchers++
        try {
            emit(emptySet())
            profileChangeFlow.collect { emit(it) }
        } finally {
            activeProfileWatchers--
        }
    }

    fun updateProfile(pubkey: String, profile: NostrProfile) {
        profileCache[pubkey] = profile
        profileChangeFlow.tryEmit(setOf(pubkey))
    }

    override fun cachedProfiles(pubkeys: Collection<String>): Map<String, NostrProfile> =
        pubkeys.mapNotNull { pubkey -> profileCache[pubkey]?.let { pubkey to it } }.toMap()

    override suspend fun requestProfiles(pubkeys: Set<String>, maxAgeMillis: Long) {
        requestedProfiles += pubkeys
    }

    override suspend fun searchUsers(query: String, relayUrl: String, limit: Int): List<Pair<String, NostrProfile>> {
        userSearches += query
        return userSearchResult.await()
    }
}

internal fun note(
    id: String,
    createdAt: Long,
    pubkey: String = "author-$id",
    content: String = "post $id",
    tags: List<List<String>> = emptyList(),
    kind: Int = 1,
) = NostrEvent(id = id, pubkey = pubkey, createdAt = createdAt, kind = kind, tags = tags, content = content, sig = "")

internal fun reaction(id: String, target: String, pubkey: String = "reactor-$id", content: String = "+") =
    note(id = id, createdAt = 10, pubkey = pubkey, content = content, tags = listOf(listOf("e", target)), kind = 7)

internal fun repost(id: String, target: String, pubkey: String = "reposter-$id") =
    note(id = id, createdAt = 10, pubkey = pubkey, content = "", tags = listOf(listOf("e", target)), kind = 6)

internal fun reply(id: String, target: String) =
    note(id = id, createdAt = 10, tags = listOf(listOf("e", target, "", "reply")))

internal fun quote(id: String, target: String, pubkey: String = "quoter-$id") =
    note(id = id, createdAt = 10, pubkey = pubkey, tags = listOf(listOf("q", target)))

/** 新しい順に [count] 件。最古は [oldest]。 */
internal fun page(prefix: String, count: Int, newest: Long): List<NostrEvent> =
    (0 until count).map { index -> note("$prefix$index", createdAt = newest - index) }

internal fun testSearchConfig(
    pageSize: Int = 3,
    maxResultEvents: Int = 600,
    pageOverflowBudget: Int = 2,
    maxTrackedEngagementTargets: Int = 100,
    engagementBatchEventLimit: Int = 800,
) = SearchConfig(
    searchRelayUrl = TEST_SEARCH_RELAY,
    pageSize = pageSize,
    maxResultEvents = maxResultEvents,
    pageOverflowBudget = pageOverflowBudget,
    maxTrackedEngagementTargets = maxTrackedEngagementTargets,
    engagementBatchEventLimit = engagementBatchEventLimit,
)
