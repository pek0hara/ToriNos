package com.nostr.torinos.network

import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.util.appLog
import com.nostr.torinos.util.loggingExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Clock

sealed interface ProfileFetchPolicy {
    data object CacheOnly : ProfileFetchPolicy

    data class CacheFirst(val maxAgeMillis: Long) : ProfileFetchPolicy {
        init {
            require(maxAgeMillis >= 0L) { "maxAgeMillisは0以上である必要があります" }
        }
    }

    data object ForceRefresh : ProfileFetchPolicy
}

/** 画面からプロフィールキャッシュとkind:0取得を利用するための統一窓口。 */
object ProfileRepository {
    fun observe(pubkey: String): Flow<NostrProfile?> = ProfileCache.observe(pubkey)

    fun observe(pubkeys: Set<String>): Flow<Map<String, NostrProfile>> =
        ProfileCache.entries
            .map { entries ->
                pubkeys.mapNotNull { pubkey ->
                    entries[pubkey]?.profile?.let { pubkey to it }
                }.toMap()
            }
            .distinctUntilChanged()

    fun observeAll(): Flow<Map<String, NostrProfile>> =
        ProfileCache.entries
            .map { entries -> entries.mapValues { it.value.profile } }
            .distinctUntilChanged()

    fun getCached(pubkey: String): NostrProfile? = ProfileCache.get(pubkey)

    fun getCached(pubkeys: Collection<String>): Map<String, NostrProfile> = ProfileCache.getAll(pubkeys)

    suspend fun ensureProfiles(
        pubkeys: Set<String>,
        policy: ProfileFetchPolicy,
        relayHint: String? = null,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        val requested = selectPubkeysToFetch(
            pubkeys = pubkeys,
            entries = ProfileCache.entries.value,
            policy = policy,
            now = now,
        )
        if (requested.isNotEmpty()) {
            ProfileFetchCoordinator.request(
                pubkeys = requested,
                relayHint = relayHint,
                force = policy is ProfileFetchPolicy.ForceRefresh,
            )
        }
    }

    suspend fun refresh(pubkey: String, relayHint: String? = null) {
        ensureProfiles(setOf(pubkey), ProfileFetchPolicy.ForceRefresh, relayHint)
    }

    suspend fun awaitProfiles(
        pubkeys: Set<String>,
        policy: ProfileFetchPolicy,
        relayHint: String? = null,
        timeoutMillis: Long = AWAIT_TIMEOUT_MS,
    ): Map<String, NostrProfile> {
        if (pubkeys.isEmpty()) return emptyMap()
        ensureProfiles(pubkeys, policy, relayHint)
        withTimeoutOrNull(timeoutMillis) {
            observe(pubkeys).first { profiles -> profiles.keys.containsAll(pubkeys) }
        }
        return getCached(pubkeys)
    }

    /** NIP-50対応リレーでユーザーを検索し、受信したkind:0を通常キャッシュにも保存する。 */
    suspend fun searchProfiles(
        query: String,
        relayUrl: String,
        limit: Int,
        timeoutMillis: Long = SEARCH_TIMEOUT_MS,
    ): List<Pair<String, NostrProfile>> = coroutineScope {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty() || limit <= 0) return@coroutineScope emptyList()
        val subscriptionId = "profile-search-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt()}"
        val profiles = linkedMapOf<String, NostrProfile>()
        val profilesMutex = Mutex()
        val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(subscriptionId).collect { event ->
                if (event.kind != PROFILE_KIND) return@collect
                ProfileCache.putEvent(event)?.let { profile ->
                    profilesMutex.withLock { profiles[event.pubkey] = profile }
                }
            }
        }
        val eose = async(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.eose(subscriptionId).first()
        }
        try {
            NostrRepository.subscribeTemporaryRelay(
                subscriptionId = subscriptionId,
                filter = NostrFilter(kinds = listOf(PROFILE_KIND), search = normalizedQuery, limit = limit),
                relayUrl = relayUrl,
            )
            withTimeoutOrNull(timeoutMillis) { eose.await() }
            profilesMutex.withLock { profiles.toList() }
        } finally {
            NostrRepository.closeTemporaryRelay(subscriptionId)
            eose.cancel()
            eventJob.cancelAndJoin()
        }
    }

    fun applyOptimistic(pubkey: String, profile: NostrProfile) {
        ProfileCache.putOptimistic(pubkey, profile)
    }

    private const val PROFILE_KIND = 0
    private const val AWAIT_TIMEOUT_MS = 8_500L
    private const val SEARCH_TIMEOUT_MS = 8_000L
}

internal fun selectPubkeysToFetch(
    pubkeys: Set<String>,
    entries: Map<String, ProfileCache.Entry>,
    policy: ProfileFetchPolicy,
    now: Long,
): Set<String> = when (policy) {
    ProfileFetchPolicy.CacheOnly -> emptySet()
    ProfileFetchPolicy.ForceRefresh -> pubkeys
    is ProfileFetchPolicy.CacheFirst -> pubkeys.filterTo(linkedSetOf()) { pubkey ->
        val entry = entries[pubkey]
        entry == null || now - entry.fetchedAt >= policy.maxAgeMillis
    }
}

private object ProfileFetchCoordinator {
    private data class Batch(
        val pubkeys: Set<String>,
        val relayHint: String?,
        val subscriptionId: String,
    )

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            loggingExceptionHandler("ProfileFetchCoordinator", "Uncaught coroutine exception"),
    )
    private val mutex = Mutex()
    private val pending = linkedMapOf<String, String?>()
    private val inFlight = linkedSetOf<String>()
    private val blockedUntil = mutableMapOf<String, Long>()
    private var flushJob: Job? = null
    private var subscriptionSerial = 0L

    suspend fun request(pubkeys: Set<String>, relayHint: String?, force: Boolean) {
        if (pubkeys.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        mutex.withLock {
            blockedUntil.entries.removeAll { it.value <= now }
            pubkeys.forEach { pubkey ->
                if (pubkey in inFlight) return@forEach
                if (!force && (blockedUntil[pubkey] ?: Long.MIN_VALUE) > now) return@forEach
                if (force) blockedUntil.remove(pubkey)
                if (pubkey !in pending || pending[pubkey] == null) {
                    pending[pubkey] = relayHint
                }
            }
            scheduleFlushLocked()
        }
    }

    private fun scheduleFlushLocked() {
        if (pending.isEmpty() || flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(BATCH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val batch = mutex.withLock {
            flushJob = null
            takeBatchLocked().also { scheduleFlushLocked() }
        } ?: return
        fetch(batch)
    }

    private fun takeBatchLocked(): Batch? {
        val first = pending.entries.firstOrNull() ?: return null
        val relayHint = first.value
        val pubkeys = pending.entries
            .asSequence()
            .filter { it.value == relayHint }
            .map { it.key }
            .take(MAX_BATCH_SIZE)
            .toCollection(linkedSetOf())
        pubkeys.forEach {
            pending.remove(it)
            inFlight.add(it)
        }
        subscriptionSerial++
        return Batch(
            pubkeys = pubkeys,
            relayHint = relayHint,
            subscriptionId = "profile-fetch-${subscriptionSerial}-${Random.nextInt()}",
        )
    }

    private suspend fun fetch(batch: Batch) {
        var completedSuccessfully = false
        val receivedPubkeys = linkedSetOf<String>()
        var session: SubscriptionSession? = null
        try {
            session = NostrRepository.openSubscription(
                SubscriptionSpec(
                    id = batch.subscriptionId,
                    filters = listOf(
                        NostrFilter(
                            kinds = listOf(PROFILE_KIND),
                            authors = batch.pubkeys.toList(),
                            limit = batch.pubkeys.size,
                        ),
                    ),
                    target = batch.relayHint?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled,
                    behavior = SubscriptionBehavior.Fetch(FETCH_TIMEOUT_MS),
                ),
            )
            session.signals.collect { signal ->
                when (signal) {
                    is SubscriptionSignal.Event -> {
                        val event = signal.event
                        if (event.kind == PROFILE_KIND && event.pubkey in batch.pubkeys) {
                            if (ProfileCache.putEvent(event) != null) {
                                receivedPubkeys.add(event.pubkey)
                            }
                        }
                    }
                    is SubscriptionSignal.FetchCompleted -> {
                        completedSuccessfully = signal.outcomes.isNotEmpty() &&
                            !signal.timedOut &&
                            signal.outcomes.values.all { it is RelayOutcome.Eose }
                    }
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            appLog("[ProfileFetchCoordinator] fetch failed: ${e::class.simpleName}: ${e.message}")
        } finally {
            runCatching { session?.close() }
            finishBatch(batch, receivedPubkeys, completedSuccessfully)
        }
    }

    private suspend fun finishBatch(
        batch: Batch,
        receivedPubkeys: Set<String>,
        completedSuccessfully: Boolean,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (completedSuccessfully) {
            ProfileCache.markFetched(batch.pubkeys, fetchedAt = now)
        }
        mutex.withLock {
            inFlight.removeAll(batch.pubkeys)
            batch.pubkeys.forEach { pubkey ->
                blockedUntil[pubkey] = now + when {
                    completedSuccessfully && pubkey !in receivedPubkeys -> MISSING_CACHE_MS
                    completedSuccessfully -> SUCCESS_COOLDOWN_MS
                    else -> FAILURE_COOLDOWN_MS
                }
            }
            scheduleFlushLocked()
        }
    }

    private const val PROFILE_KIND = 0
    private const val MAX_BATCH_SIZE = 100
    private const val BATCH_DELAY_MS = 200L
    private const val FETCH_TIMEOUT_MS = 8_000L
    private const val MISSING_CACHE_MS = 60_000L
    private const val SUCCESS_COOLDOWN_MS = 5_000L
    private const val FAILURE_COOLDOWN_MS = 5_000L
}
