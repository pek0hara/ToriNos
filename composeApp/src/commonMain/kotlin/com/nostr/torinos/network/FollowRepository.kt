package com.nostr.torinos.network

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.logException
import com.nostr.torinos.util.loggingExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * 自分のフォローリスト（kind:3）を管理するリポジトリ。
 * アプリ全体でシングルトンとして使用する。
 */
object FollowRepository {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            loggingExceptionHandler("FollowRepository", "Uncaught coroutine exception"),
    )

    private val _followedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val followedPubkeys: StateFlow<Set<String>> = _followedPubkeys.asStateFlow()

    /** ロード完了したか（null=未ロード、false=ロード中、true=完了） */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var ownPubkey: String? = null
    private var loadGeneration = 0L
    private var updateRequestGeneration = 0L
    private var loadJob: Job? = null
    private val updateMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadJob = startLoadJob()
    }

    /** アカウント切り替え時に呼ぶ。フォローリストをリセットして新アカウントのリストを再取得する。 */
    fun reload() {
        restartLoad(reset = true)
    }

    /** 手動更新時に呼ぶ。既存のフォローリスト表示は残したままバックグラウンドで再取得する。 */
    fun refresh() {
        restartLoad(reset = false)
    }

    private fun restartLoad(reset: Boolean) {
        val previousSubId = subscriptionId(loadGeneration)
        loadGeneration++
        if (reset) {
            _loaded.value = false
            ownPubkey = null
            _followedPubkeys.value = emptySet()
        }
        loadJob?.cancel()
        NostrRepository.close(previousSubId)
        loadJob = startLoadJob()
    }

    private fun startLoadJob(): Job {
        val generation = loadGeneration
        return scope.launch {
            try {
                load(generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("FollowRepository", e, "reload() failed")
            }
        }
    }

    private fun subscriptionId(generation: Long): String = "follow-list-$generation"

    private suspend fun load(generation: Long) {
        ownPubkey = loadPublicKey()
        val pk = ownPubkey ?: run {
            if (generation == loadGeneration) _loaded.value = true
            return
        }
        val subId = subscriptionId(generation)

        // EOSE を待ってから最新の kind:3 を採用する
        val cached = loadFollowCache(pk)
        var latestCreatedAt = cached?.createdAt ?: 0L
        var latestFollows = cached?.pubkeys?.toSet() ?: emptySet()
        var receivedFollowEvent = false
        val targetRelayUrls = NostrRepository.targetRelayUrls(RelayTarget.AllEnabled)
        val completedRelayUrls = mutableSetOf<String>()

        if (cached != null && generation == loadGeneration) {
            _followedPubkeys.value = latestFollows
            _loaded.value = true
        }

        // coroutineScope を使い eventJob を子コルーチンとして管理する。
        // reload() で loadJob がキャンセルされると eventJob も連動してキャンセルされる。
        coroutineScope {
            val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    NostrRepository.events(subId).collect { event ->
                        if (event.kind == 3 && event.createdAt > latestCreatedAt) {
                            latestCreatedAt = event.createdAt
                            latestFollows = event.tags
                                .filter { it.size >= 2 && it[0] == "p" }
                                .map { it[1] }
                                .toSet()
                            receivedFollowEvent = true
                            publishLoadedFollows(latestFollows, latestCreatedAt, generation, persist = true)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logException("FollowRepository", e, "events collector failed")
                }
            }

            try {
                val eoseComplete = CompletableDeferred<Unit>()
                val eoseJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    NostrRepository.eoseRelays(subId).collect { relayUrl ->
                        completedRelayUrls.add(relayUrl)
                        if (targetRelayUrls.isEmpty() || completedRelayUrls.containsAll(targetRelayUrls)) {
                            eoseComplete.complete(Unit)
                        }
                    }
                }
                NostrRepository.subscribe(
                    subId,
                    NostrFilter(kinds = listOf(3), authors = listOf(pk), limit = 1),
                )
                withTimeoutOrNull(FOLLOW_LIST_EOSE_TIMEOUT_MS) { eoseComplete.await() }
                eoseJob.cancel()
                // タイムアウトを含め、初回取得の試行が終わったら表示上のロードは完了させる。
                // フォロー更新時には fetchLatestFollowList() で最新状態を再取得するため、ここで
                // 空リストを公開しても、表示用の空状態だけを元に kind:3 を上書きすることはない。
                publishLoadedFollows(latestFollows, latestCreatedAt, generation, persist = receivedFollowEvent)
                eventJob.cancel()
                NostrRepository.close(subId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("FollowRepository", e, "eose collector failed")
            }
        }
    }

    fun isFollowing(pubkey: String): Boolean = _followedPubkeys.value.contains(pubkey)

    suspend fun follow(pubkey: String): Result<Unit> = updateFollowList { follows ->
        follows + pubkey
    }

    suspend fun unfollow(pubkey: String): Result<Unit> = updateFollowList { follows ->
        follows - pubkey
    }

    private suspend fun updateFollowList(
        transform: (Set<String>) -> Set<String>,
    ): Result<Unit> {
        // プロフィール画面が閉じられても更新を完了させる。
        val completion = CompletableDeferred<Result<Unit>>()
        scope.launch {
            completion.complete(performFollowListUpdate(transform))
        }
        return completion.await()
    }

    private suspend fun performFollowListUpdate(
        transform: (Set<String>) -> Set<String>,
    ): Result<Unit> = updateMutex.withLock {
        val generation = loadGeneration
        val accountPubkey = ownPubkey
        if (!_loaded.value || accountPubkey == null) {
            return@withLock Result.failure(Exception("フォロー一覧の読み込みが完了していません"))
        }

        runCatching {
            // 更新の直前にリレー上の最新 kind:3 を取得し、
            // ローカルキャッシュの欠落や遅延を上書きしないようにする。
            val remote = fetchLatestFollowList(accountPubkey)

            check(generation == loadGeneration && _loaded.value) {
                "アカウントが切り替わったためフォロー更新を中止しました"
            }

            val privKey = checkNotNull(KeyStorage.loadPrivateKey()) {
                "秘密鍵が設定されていません"
            }
            val newSet = transform(remote.pubkeys)
            val tags = newSet.map { listOf("p", it) }
            val createdAt = Clock.System.now().epochSeconds.coerceAtLeast(remote.createdAt + 1L)
            val event = signEvent(
                privKey,
                content = "",
                kind = 3,
                tags = tags,
                createdAt = createdAt,
            )

            check(
                generation == loadGeneration &&
                    _loaded.value &&
                    event.pubkey == accountPubkey
            ) { "アカウントが切り替わったためフォロー更新を中止しました" }

            val publishResult = NostrRepository.publishToRelaysWithResult(event, remote.relayUrls)
            check(publishResult.succeededRelays.isNotEmpty()) {
                "取得できたリレーへのフォロー更新に失敗しました"
            }
            if (generation == loadGeneration) {
                _followedPubkeys.value = newSet
                saveFollowCache(accountPubkey, newSet, event.createdAt)
            }
        }
    }

    private suspend fun fetchLatestFollowList(pubkey: String): RemoteFollowList = coroutineScope {
        val requestId = ++updateRequestGeneration
        val subId = "follow-update-$requestId"
        val targetRelayUrls = NostrRepository.targetRelayUrls(RelayTarget.AllEnabled)
        check(targetRelayUrls.isNotEmpty()) { "有効なリレーがありません" }

        var latestEventCreatedAt = -1L
        var latestEventId = ""
        var latestPubkeys = emptySet<String>()
        val completedRelayUrls = mutableSetOf<String>()
        val eoseComplete = CompletableDeferred<Unit>()

        val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(subId).collect { event ->
                val isNewer = event.kind == 3 && (
                    event.createdAt > latestEventCreatedAt ||
                        (event.createdAt == latestEventCreatedAt &&
                            (latestEventId.isEmpty() || event.id < latestEventId))
                    )
                if (isNewer) {
                    latestEventCreatedAt = event.createdAt
                    latestEventId = event.id
                    latestPubkeys = event.tags
                        .filter { it.size >= 2 && it[0] == "p" }
                        .map { it[1] }
                        .toSet()
                }
            }
        }
        val eoseJob = launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.eoseRelays(subId).collect { relayUrl ->
                completedRelayUrls.add(relayUrl)
                if (completedRelayUrls.containsAll(targetRelayUrls)) {
                    eoseComplete.complete(Unit)
                }
            }
        }

        try {
            NostrRepository.subscribe(
                subId,
                NostrFilter(kinds = listOf(3), authors = listOf(pubkey), limit = 1),
            )
            withTimeoutOrNull(FOLLOW_LIST_EOSE_TIMEOUT_MS) {
                eoseComplete.await()
            }
            val readableRelayUrls = completedRelayUrls.toSet()
            check(readableRelayUrls.isNotEmpty()) {
                "リレーから最新のフォロー一覧を取得できませんでした"
            }
            RemoteFollowList(
                pubkeys = latestPubkeys,
                createdAt = latestEventCreatedAt.coerceAtLeast(0L),
                relayUrls = readableRelayUrls,
            )
        } finally {
            eventJob.cancel()
            eoseJob.cancel()
            NostrRepository.close(subId)
        }
    }

    private suspend fun publishLoadedFollows(
        follows: Set<String>,
        createdAt: Long,
        generation: Long,
        persist: Boolean,
    ) {
        if (generation != loadGeneration) return
        _followedPubkeys.value = follows
        _loaded.value = true
        if (persist) {
            ownPubkey?.let { saveFollowCache(it, follows, createdAt) }
        }
    }

    private suspend fun loadFollowCache(pubkey: String): FollowCache? =
        runCatching {
            LocalSettingsStorage.getString(cacheKey(pubkey))
                ?.let { json.decodeFromString<FollowCache>(it) }
        }.getOrNull()

    private suspend fun saveFollowCache(pubkey: String, follows: Set<String>, createdAt: Long) {
        runCatching {
            val cache = FollowCache(
                createdAt = createdAt.takeIf { it > 0 } ?: Clock.System.now().epochSeconds,
                pubkeys = follows.sorted(),
            )
            LocalSettingsStorage.putString(cacheKey(pubkey), json.encodeToString(cache))
        }.onFailure { e ->
            logException("FollowRepository", e, "saveFollowCache() failed")
        }
    }

    private fun cacheKey(pubkey: String): String = "$FOLLOW_CACHE_KEY_PREFIX$pubkey"

    @Serializable
    private data class FollowCache(
        val createdAt: Long = 0L,
        val pubkeys: List<String> = emptyList(),
    )

    private data class RemoteFollowList(
        val pubkeys: Set<String>,
        val createdAt: Long,
        val relayUrls: Set<String>,
    )

    private const val FOLLOW_CACHE_KEY_PREFIX = "follow_list_cache_"
    private const val FOLLOW_LIST_EOSE_TIMEOUT_MS = 10_000L
}
