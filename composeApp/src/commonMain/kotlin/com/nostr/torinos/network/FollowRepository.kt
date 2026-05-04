package com.nostr.torinos.network

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.logException
import com.nostr.torinos.util.loggingExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val subId = "follow-list"
    private var loadJob: Job? = null

    init {
        loadJob = scope.launch {
            try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("FollowRepository", e, "load() failed")
            }
        }
    }

    /** アカウント切り替え時に呼ぶ。フォローリストをリセットして新アカウントのリストを再取得する。 */
    fun reload() {
        _followedPubkeys.value = emptySet()
        _loaded.value = false
        loadJob?.cancel()
        NostrRepository.close(subId)
        loadJob = scope.launch {
            try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("FollowRepository", e, "reload() failed")
            }
        }
    }

    private suspend fun load() {
        ownPubkey = loadPublicKey()
        val pk = ownPubkey ?: return

        NostrRepository.subscribe(
            subId,
            NostrFilter(kinds = listOf(3), authors = listOf(pk), limit = 1),
        )

        // EOSE を待ってから最新の kind:3 を採用する
        var latestCreatedAt = 0L
        var latestFollows = emptySet<String>()

        // coroutineScope を使い eventJob を子コルーチンとして管理する。
        // reload() で loadJob がキャンセルされると eventJob も連動してキャンセルされる。
        coroutineScope {
            val eventJob = launch {
                try {
                    NostrRepository.events(subId).collect { event ->
                        if (event.kind == 3 && event.createdAt > latestCreatedAt) {
                            latestCreatedAt = event.createdAt
                            latestFollows = event.tags
                                .filter { it.size >= 2 && it[0] == "p" }
                                .map { it[1] }
                                .toSet()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logException("FollowRepository", e, "events collector failed")
                }
            }

            try {
                NostrRepository.eose(subId).collect {
                    _followedPubkeys.value = latestFollows
                    _loaded.value = true
                    eventJob.cancel()
                    NostrRepository.close(subId)
                }
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
        }
    }
}
