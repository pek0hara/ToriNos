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
    private var loadJob: Job? = null
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
            _followedPubkeys.value = emptySet()
            _loaded.value = false
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
                if (receivedFollowEvent || cached == null) {
                    publishLoadedFollows(latestFollows, latestCreatedAt, generation, persist = receivedFollowEvent)
                }
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

    suspend fun follow(pubkey: String): Result<Unit> = updateFollowList(
        _followedPubkeys.value + pubkey
    )

    suspend fun unfollow(pubkey: String): Result<Unit> = updateFollowList(
        _followedPubkeys.value - pubkey
    )

    private suspend fun updateFollowList(newSet: Set<String>): Result<Unit> {
        val privKey = KeyStorage.loadPrivateKey()
            ?: return Result.failure(Exception("秘密鍵が設定されていません"))

        return runCatching {
            val tags = newSet.map { listOf("p", it) }
            val event = signEvent(privKey, content = "", kind = 3, tags = tags)
            NostrRepository.publish(event)
            _followedPubkeys.value = newSet
            ownPubkey?.let { saveFollowCache(it, newSet, event.createdAt) }
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

    private const val FOLLOW_CACHE_KEY_PREFIX = "follow_list_cache_"
    private const val FOLLOW_LIST_EOSE_TIMEOUT_MS = 10_000L
}
