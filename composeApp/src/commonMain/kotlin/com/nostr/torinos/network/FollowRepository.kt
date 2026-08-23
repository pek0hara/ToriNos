package com.nostr.torinos.network

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
 * AccountSession ごとに生成され、セッション終了時に購読と処理を破棄する。
 */
class FollowRepository internal constructor(
    private val accountSession: AccountSession,
    private val scope: CoroutineScope,
) {

    private val _followedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val followedPubkeys: StateFlow<Set<String>> = _followedPubkeys.asStateFlow()

    /** ロード完了したか（null=未ロード、false=ロード中、true=完了） */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var ownPubkey: String? = null
    private var latestFollowEvent: NostrEvent? = null
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var started = false
    private val updateMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    internal fun start() {
        if (started) return
        started = true
        restartLoad(reset = true)
    }

    /** アカウント切り替え時に呼ぶ。フォローリストをリセットして新アカウントのリストを再取得する。 */
    fun reload() {
        if (!started) return
        restartLoad(reset = true)
    }

    /** 手動更新時に呼ぶ。既存のフォローリスト表示は残したままバックグラウンドで再取得する。 */
    fun refresh() {
        if (!started) return
        restartLoad(reset = false)
    }

    private fun restartLoad(reset: Boolean) {
        val previousSubId = subscriptionId(loadGeneration)
        loadGeneration++
        if (reset) {
            _loaded.value = false
            ownPubkey = accountSession.pubkey
            latestFollowEvent = null
            _followedPubkeys.value = emptySet()
        }
        loadJob?.cancel()
        NostrRepository.close(previousSubId)
        loadJob = startLoadJob()
    }

    private fun startLoadJob(): Job {
        val generation = loadGeneration
        val session = accountSession
        return scope.launch {
            try {
                load(generation, session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("FollowRepository", e, "reload() failed")
            }
        }
    }

    private fun subscriptionId(generation: Long): String =
        "follow-list-${accountSession.sessionId.takeLast(16)}-$generation"

    private suspend fun load(generation: Long, session: AccountSession) {
        val pk = session.pubkey
        if (generation != loadGeneration) return
        ownPubkey = pk
        val subId = subscriptionId(generation)

        // EOSE を待ってから最新の kind:3 を採用する
        val cached = loadFollowCache(pk)
        var latestEvent = cached?.event?.takeIf { it.kind == 3 && it.pubkey == pk }
        var latestCreatedAt = latestEvent?.createdAt ?: cached?.createdAt ?: 0L
        var latestFollows = latestEvent?.followedPubkeys() ?: cached?.pubkeys?.toSet() ?: emptySet()
        var receivedFollowEvent = false
        val targetRelayUrls = NostrRepository.targetRelayUrls(RelayTarget.AllEnabled)
        val completedRelayUrls = mutableSetOf<String>()

        if (cached != null && generation == loadGeneration) {
            latestFollowEvent = latestEvent
            _followedPubkeys.value = latestFollows
            _loaded.value = true
        }

        // coroutineScope を使い eventJob を子コルーチンとして管理する。
        // reload() で loadJob がキャンセルされると eventJob も連動してキャンセルされる。
        coroutineScope {
            val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    NostrRepository.events(subId).collect { event ->
                        if (event.kind == 3 && event.pubkey == pk && event.isNewerThan(latestEvent, latestCreatedAt)) {
                            latestEvent = event
                            latestCreatedAt = event.createdAt
                            latestFollows = event.followedPubkeys()
                            receivedFollowEvent = true
                            publishLoadedFollows(
                                follows = latestFollows,
                                event = event,
                                generation = generation,
                                persist = true,
                            )
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
                // 表示用の空状態だけでは latestFollowEvent が設定されないため、
                // kind:3 未取得のままフォロー更新を公開することはない。
                publishLoadedFollows(
                    follows = latestFollows,
                    event = latestEvent,
                    generation = generation,
                    persist = receivedFollowEvent,
                )
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

    internal suspend fun latestFollowListEvent(): NostrEvent? {
        latestFollowEvent?.let { return it }
        val pubkey = ownPubkey ?: return null
        return loadFollowCache(pubkey)?.event
            ?.takeIf { it.kind == 3 && it.pubkey == pubkey }
            ?.also { latestFollowEvent = it }
    }

    /** Damus と同様に、新規アカウント作成時だけ空の kind:3 を初期化する。 */
    suspend fun initializeNewAccountFollowList(): Result<Unit> {
        val signer = accountSession.signer
        val event = signer.sign(
            content = "",
            kind = 3,
            tags = emptyList(),
        )

        // 通信に失敗しても、新規生成した鍵の正当な初期イベントは端末に残す。
        saveFollowCache(event.pubkey, event)
        return runCatching {
            NostrRepository.publish(event)
            Unit
        }
    }

    suspend fun follow(pubkey: String): Result<Unit> = updateFollowList { event ->
        editFollowEvent(event, pubkey, shouldFollow = true)
    }

    suspend fun unfollow(pubkey: String): Result<Unit> = updateFollowList { event ->
        editFollowEvent(event, pubkey, shouldFollow = false)
    }

    private suspend fun updateFollowList(
        transform: (NostrEvent) -> FollowEventEdit?,
    ): Result<Unit> {
        // プロフィール画面が閉じられても更新を完了させる。
        val completion = CompletableDeferred<Result<Unit>>()
        scope.launch {
            completion.complete(performFollowListUpdate(transform))
        }
        return completion.await()
    }

    private suspend fun performFollowListUpdate(
        transform: (NostrEvent) -> FollowEventEdit?,
    ): Result<Unit> = updateMutex.withLock {
        val generation = loadGeneration
        val session = accountSession
        val accountPubkey = ownPubkey
        if (!_loaded.value || accountPubkey == null) {
            return@withLock Result.failure(Exception("フォロー一覧の読み込みが完了していません"))
        }

        runCatching {
            // Damus と同様に、操作時は読み込み済みの kind:3 を直接編集する。
            // 操作ごとの EOSE 待ちは行わない。
            val existingEvent = checkNotNull(
                latestFollowEvent?.takeIf { it.kind == 3 && it.pubkey == accountPubkey },
            ) {
                "既存のフォロー一覧を取得できないため、更新を中止しました"
            }
            val targetRelayUrls = NostrRepository.targetRelayUrls(RelayTarget.AllEnabled)
            check(targetRelayUrls.isNotEmpty()) { "有効なリレーがありません" }

            val edit = transform(existingEvent) ?: return@runCatching
            val newSet = edit.tags.followedPubkeys()
            val createdAt = Clock.System.now().epochSeconds.coerceAtLeast(existingEvent.createdAt + 1L)
            val event = session.signer.sign(
                content = edit.content,
                kind = 3,
                tags = edit.tags,
                createdAt = createdAt,
            )

            check(
                generation == loadGeneration &&
                    _loaded.value &&
                    accountSession.sessionId == session.sessionId &&
                    event.pubkey == accountPubkey
            ) { "アカウントが切り替わったためフォロー更新を中止しました" }

            // 1リレーに送信できた時点で操作を完了し、残りはバックグラウンドで継続する。
            val publishResult = NostrRepository.publishToRelaysUntilFirstSuccess(event, targetRelayUrls)
            check(publishResult.succeededRelays.isNotEmpty()) {
                "フォロー更新の送信に失敗しました"
            }
            if (generation == loadGeneration) {
                latestFollowEvent = event
                _followedPubkeys.value = newSet
                saveFollowCache(accountPubkey, event)
            }
        }
    }

    private suspend fun publishLoadedFollows(
        follows: Set<String>,
        event: NostrEvent?,
        generation: Long,
        persist: Boolean,
    ) {
        if (generation != loadGeneration) return
        if (event != null) latestFollowEvent = event
        _followedPubkeys.value = follows
        _loaded.value = true
        if (persist && event != null) {
            ownPubkey?.let { saveFollowCache(it, event) }
        }
    }

    private suspend fun loadFollowCache(pubkey: String): FollowCache? =
        runCatching {
            LocalSettingsStorage.getString(cacheKey(pubkey))
                ?.let { json.decodeFromString<FollowCache>(it) }
        }.getOrNull()

    private suspend fun saveFollowCache(pubkey: String, event: NostrEvent) {
        runCatching {
            val cache = FollowCache(
                createdAt = event.createdAt.takeIf { it > 0 } ?: Clock.System.now().epochSeconds,
                pubkeys = event.followedPubkeys().sorted(),
                event = event,
            )
            LocalSettingsStorage.putString(cacheKey(pubkey), json.encodeToString(cache))
        }.onFailure { e ->
            logException("FollowRepository", e, "saveFollowCache() failed")
        }
    }

    private fun cacheKey(pubkey: String): String = "$FOLLOW_CACHE_KEY_PREFIX$pubkey"

    internal fun close() {
        started = false
        loadGeneration++
        loadJob?.cancel()
        NostrRepository.close(subscriptionId(loadGeneration - 1))
        loadJob = null
    }

    @Serializable
    private data class FollowCache(
        val createdAt: Long = 0L,
        val pubkeys: List<String> = emptyList(),
        val event: NostrEvent? = null,
    )

    private val FOLLOW_CACHE_KEY_PREFIX = "follow_list_cache_"
    private val FOLLOW_LIST_EOSE_TIMEOUT_MS = 10_000L
}
