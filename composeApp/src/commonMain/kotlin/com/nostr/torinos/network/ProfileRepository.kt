package com.nostr.torinos.network

import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrEvent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nostr.torinos.util.withLock
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
        ProfileCache.observe(pubkeys)

    internal fun observeChanges(): Flow<Set<String>> = ProfileCache.observeChanges()

    fun getCached(pubkey: String): NostrProfile? = ProfileCache.get(pubkey)

    fun getCached(pubkeys: Collection<String>): Map<String, NostrProfile> = ProfileCache.getAll(pubkeys)

    suspend fun ensureProfiles(
        pubkeys: Set<String>,
        policy: ProfileFetchPolicy,
        relayHint: String? = null,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        val entries = ProfileCache.snapshotEntries()
        val requested = selectPubkeysToFetch(
            pubkeys = pubkeys,
            entries = entries,
            policy = policy,
            now = now,
        )
        recordCacheUse(pubkeys, entries)
        if (requested.isNotEmpty()) {
            ProfileFetchCoordinator.request(
                pubkeys = requested,
                relayHint = relayHint,
                force = policy is ProfileFetchPolicy.ForceRefresh,
            )
        }
    }

    // 実機確認用: 表示のために求められたプロフィールが、キャッシュにあったか(古くても表示はできる)。
    private val cacheUseLock = com.nostr.torinos.util.SynchronousLock()
    private var cacheUseRequested = 0
    private var cacheUseHit = 0

    private fun recordCacheUse(pubkeys: Set<String>, entries: Map<String, ProfileCache.Entry>) {
        val hits = pubkeys.count { it in entries }
        val (requested, hit) = cacheUseLock.withLock {
            val before = cacheUseRequested / 100
            cacheUseRequested += pubkeys.size
            cacheUseHit += hits
            if (cacheUseRequested / 100 == before && cacheUseRequested > pubkeys.size) return
            cacheUseRequested to cacheUseHit
        }
        com.nostr.torinos.util.persistentCacheLog { "profile use requested=$requested cached=$hit" }
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

    /**
     * [pubkey] のプロフィールを、取得日時が [maxAgeMillis] より古ければ取り直し、その取得が終わるまで待って返す。
     * [awaitProfiles] はキャッシュにあれば取り直しを待たずに返すため、古い値では困るとき（Zap の受け取り先など）に使う。
     * 取得が [timeoutMillis] で終わらなければ、その時点のキャッシュを返す。
     */
    suspend fun awaitFresh(
        pubkey: String,
        maxAgeMillis: Long,
        relayHint: String? = null,
        timeoutMillis: Long = AWAIT_TIMEOUT_MS,
    ): NostrProfile? {
        ensureProfiles(setOf(pubkey), ProfileFetchPolicy.CacheFirst(maxAgeMillis), relayHint)
        withTimeoutOrNull(timeoutMillis) { ProfileFetchCoordinator.awaitSettled(pubkey) }
        return getCached(pubkey)
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

    /** 自分で署名して送った kind:0 を、元イベントごと手元の最新として持つ（Buzz への送り直しに使う）。 */
    fun recordPublished(event: NostrEvent) {
        if (event.kind == PROFILE_KIND) ProfileCache.putEvent(event)
    }

    /** 手元で最新の kind:0 イベント（署名済みのまま送り直せる）。 */
    fun latestEvent(pubkey: String): NostrEvent? = ProfileCache.latestEvent(pubkey)

    /**
     * [relayUrl] だけに [pubkey] の kind:0 を聞く。Buzz のコミュニティのリレーにある版を確かめるのに使う。
     * 受け取った版はキャッシュにも入れる（Buzz のアプリで編集された新しい版を取りこぼさない）。
     * 認証が要るリレーでは、呼ぶ側が接続を常時接続の側に置いておく。
     */
    internal suspend fun fetchFromRelay(
        pubkey: String,
        relayUrl: String,
        timeoutMillis: Long = RELAY_CHECK_TIMEOUT_MS,
    ): RelayProfileCheck {
        val result = fetchFiniteEvents(
            SubscriptionSpec(
                id = "profile-at-${Random.nextLong().toULong().toString(16)}",
                filters = listOf(NostrFilter(kinds = listOf(PROFILE_KIND), authors = listOf(pubkey), limit = 1)),
                target = RelayTarget.Explicit(setOf(relayUrl)),
                behavior = SubscriptionBehavior.Fetch(timeoutMillis),
                replayCachedEvents = false,
            ),
        )
        if (result.relays[relayUrl]?.outcome != RelayOutcome.Eose) return RelayProfileCheck.Unanswered
        val latest = result.events
            .filter { it.kind == PROFILE_KIND && it.pubkey == pubkey }
            .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenByDescending { it.id })
        latest?.let(ProfileCache::putEvent)
        return RelayProfileCheck.Answered(latest)
    }

    private const val RELAY_CHECK_TIMEOUT_MS = 10_000L

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
        /** 先頭リレーでの取得に失敗し、全リレーへ広げた再取得。 */
        val escalated: Boolean,
    )

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            loggingExceptionHandler("ProfileFetchCoordinator", "Uncaught coroutine exception"),
    )
    private val mutex = Mutex()
    private val pending = linkedMapOf<String, String?>()
    private val inFlight = linkedSetOf<String>()
    private val escalatedPubkeys = linkedSetOf<String>()
    private val blockedUntil = mutableMapOf<String, Long>()
    private var flushJob: Job? = null
    private var subscriptionSerial = 0L
    /** 取得待ち・取得中の pubkey（[pending] と [inFlight]）。[mutex] の中で書き換えたら [publishBusyLocked] で更新する。 */
    private val busy = MutableStateFlow<Set<String>>(emptySet())

    private fun publishBusyLocked() {
        busy.value = pending.keys + inFlight
    }

    /** [pubkey] の取得待ち・取得中（全リレーへの広げ直しも含む）が終わるまで待つ。何も頼んでいなければすぐ戻る。 */
    suspend fun awaitSettled(pubkey: String) {
        busy.first { pubkey !in it }
    }

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
            publishBusyLocked()
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
            takeBatchLocked().also {
                publishBusyLocked()
                scheduleFlushLocked()
            }
        } ?: return
        fetch(batch)
    }

    private fun takeBatchLocked(): Batch? {
        val first = pending.entries.firstOrNull() ?: return null
        val relayHint = first.value
        val escalated = first.key in escalatedPubkeys
        val pubkeys = pending.entries
            .asSequence()
            .filter { it.value == relayHint && (it.key in escalatedPubkeys) == escalated }
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
            escalated = escalated,
        )
    }

    private suspend fun fetch(batch: Batch) {
        var completedSuccessfully = false
        val receivedPubkeys = linkedSetOf<String>()
        val pendingEvents = mutableListOf<NostrEvent>()
        var session: SubscriptionSession? = null
        var progressive = false
        fun applyPendingEvents() {
            if (pendingEvents.isEmpty()) return
            receivedPubkeys.addAll(ProfileCache.putEvents(pendingEvents).keys)
            pendingEvents.clear()
        }
        try {
            // リレー指定がなければ、まず先頭の少数リレーだけに聞く。全リレーに同じ kind:0 を聞くと
            // 同一イベントが台数分返る。取れなかった分は finishBatch で全リレーへ広げる。
            val primaryRelays = if (
                useProgressiveProfileFetch(
                    relayHint = batch.relayHint,
                    escalated = batch.escalated,
                    anyCached = batch.pubkeys.any { ProfileCache.get(it) != null },
                )
            ) {
                profilePrimaryRelays(NostrRepository.targetRelayUrls(RelayTarget.AllEnabled), PRIMARY_RELAY_COUNT)
            } else {
                null
            }
            progressive = primaryRelays != null
            val target = profileFetchTarget(
                relayHint = batch.relayHint,
                primaryRelays = primaryRelays,
                extraRelays = ProfileExtraRelays.current(),
            )
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
                    target = target,
                    behavior = SubscriptionBehavior.Fetch(
                        if (progressive) PRIMARY_FETCH_TIMEOUT_MS else FETCH_TIMEOUT_MS,
                    ),
                ),
            )
            session.collectRecovering { signal ->
                when (signal) {
                    is SubscriptionSignal.Event -> {
                        val event = signal.event
                        if (event.kind == PROFILE_KIND && event.pubkey in batch.pubkeys) {
                            pendingEvents.add(event)
                        }
                    }
                    is SubscriptionSignal.FetchCompleted -> {
                        applyPendingEvents()
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
            applyPendingEvents()
            runCatching { session?.close() }
            finishBatch(batch, receivedPubkeys, completedSuccessfully, progressive)
        }
    }

    private suspend fun finishBatch(
        batch: Batch,
        receivedPubkeys: Set<String>,
        completedSuccessfully: Boolean,
        progressive: Boolean,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        val fallbackPubkeys = profileFallbackPubkeys(
            requestedPubkeys = batch.pubkeys,
            receivedPubkeys = receivedPubkeys,
            relayHint = batch.relayHint,
            progressive = progressive,
        )
        if (completedSuccessfully) {
            ProfileCache.markFetched(batch.pubkeys, fetchedAt = now)
        }
        mutex.withLock {
            inFlight.removeAll(batch.pubkeys)
            escalatedPubkeys.removeAll(batch.pubkeys)
            batch.pubkeys.forEach { pubkey ->
                if (pubkey in fallbackPubkeys) {
                    blockedUntil.remove(pubkey)
                    pending[pubkey] = null
                    escalatedPubkeys.add(pubkey)
                } else {
                    blockedUntil[pubkey] = now + when {
                        completedSuccessfully && pubkey !in receivedPubkeys -> MISSING_CACHE_MS
                        completedSuccessfully -> SUCCESS_COOLDOWN_MS
                        else -> FAILURE_COOLDOWN_MS
                    }
                }
            }
            publishBusyLocked()
            scheduleFlushLocked()
        }
    }

    private const val PROFILE_KIND = 0
    private const val MAX_BATCH_SIZE = 100
    private const val BATCH_DELAY_MS = 200L
    private const val FETCH_TIMEOUT_MS = 8_000L
    private const val PRIMARY_RELAY_COUNT = 2
    private const val PRIMARY_FETCH_TIMEOUT_MS = 3_000L
    private const val MISSING_CACHE_MS = 60_000L
    private const val SUCCESS_COOLDOWN_MS = 5_000L
    private const val FAILURE_COOLDOWN_MS = 5_000L
}

/**
 * kind 0 を問い合わせる先。リレー指定 → 先頭の少数リレー(段階取得) → 全リレー の順に決める。
 *
 * [extraRelays](Buzz のコミュニティなど、利用者の kind 0 をそこにしか置かないリレー)へは、そのリレーを指定した
 * 取得(グループの画面からの取得)でだけ聞く。全リレーへの取得には加えない。加えると、フィードで見かけた人の
 * 問い合わせが認証済みの身元でコミュニティへ伝わり、そのリレーが答えない間は全体の取得が失敗扱いになる。
 */
internal fun profileFetchTarget(
    relayHint: String?,
    primaryRelays: Set<String>?,
    extraRelays: Set<String>,
): RelayTarget = when {
    // Single は設定済みのリレーに限られ、Buzz のリレーは落ちてしまう。Buzz のリレーは明示して聞く。
    relayHint != null && relayHint in extraRelays -> RelayTarget.Explicit(setOf(relayHint))
    relayHint != null -> RelayTarget.Single(relayHint)
    primaryRelays != null -> RelayTarget.Explicit(primaryRelays)
    else -> RelayTarget.AllEnabled
}

/**
 * 通常のリレー設定には入っていないが、指定されればプロフィール(kind 0)を聞きに行くリレー。
 * いまのアカウントの Buzz コミュニティを [ChatAccount][com.nostr.torinos.chat.ChatAccount] が登録する。
 * 認証が要るリレーなので、認証を許したアカウントが持ち主のときだけ使う。
 */
object ProfileExtraRelays {
    private val lock = com.nostr.torinos.util.SynchronousLock()
    private var owner: Any? = null
    private var relays: Set<String> = emptySet()

    fun set(owner: Any, relays: Set<String>) {
        lock.withLock {
            this.owner = owner
            this.relays = relays
        }
    }

    /** [owner] が今の持ち主のときだけ消す(切り替え後の古いアカウントの後片付けで消さない)。 */
    fun clear(owner: Any) {
        lock.withLock {
            if (this.owner === owner) {
                this.owner = null
                relays = emptySet()
            }
        }
    }

    fun current(): Set<String> = lock.withLock { relays }
}

internal fun profileFallbackPubkeys(
    requestedPubkeys: Set<String>,
    receivedPubkeys: Set<String>,
    relayHint: String?,
    progressive: Boolean = false,
): Set<String> = if (relayHint == null && !progressive) {
    emptySet()
} else {
    requestedPubkeys - receivedPubkeys
}

/**
 * 先頭の少数リレーへ先に聞く段階取得を使うか。キャッシュ済みのプロフィールの再取得(期限切れ・強制更新)は、
 * 先頭のリレーが古い版を返しても「取得できた」と見なされ、他のリレーにある新しい版を取り逃がすため、
 * 最初から全リレーへ聞く。段階取得は、まだ1件も持っていない(初回取得)の場合だけに使う。
 */
internal fun useProgressiveProfileFetch(relayHint: String?, escalated: Boolean, anyCached: Boolean): Boolean =
    relayHint == null && !escalated && !anyCached

/**
 * リレー指定がないプロフィール取得で最初に聞くリレー。設定順の先頭 [count] 台。
 * 台数が [count] 以下なら段階取得の意味がないため null(全リレーへ直接聞く)。
 */
internal fun profilePrimaryRelays(allRelays: Collection<String>, count: Int): Set<String>? =
    if (allRelays.size <= count) null else allRelays.take(count).toCollection(linkedSetOf())

/** 1つのリレーに kind:0 を聞いた結果。 */
internal sealed interface RelayProfileCheck {
    /** EOSE まで答えた。[event] が null ならそのリレーには無い。 */
    data class Answered(val event: NostrEvent?) : RelayProfileCheck
    /** 答えなかった（認証できない、時間切れなど）。無いのか分からないので送り直さない。 */
    data object Unanswered : RelayProfileCheck
}

/**
 * リレーへ送り直すべき kind:0 を返す。リレーが答え、手元の版の方が新しい（またはリレーに無い）ときだけ送る。
 * 同じ作成日時なら NIP-01 の規則（ID が小さい方が新しい）で比べる。リレーの方が新しいときは送らない。
 */
internal fun profileEventToResend(local: NostrEvent?, check: RelayProfileCheck): NostrEvent? {
    if (local == null || local.kind != 0 || check !is RelayProfileCheck.Answered) return null
    val remote = check.event ?: return local
    if (remote.pubkey != local.pubkey) return null
    val localIsNewer = local.createdAt > remote.createdAt ||
        (local.createdAt == remote.createdAt && local.id < remote.id)
    return local.takeIf { localIsNewer }
}
