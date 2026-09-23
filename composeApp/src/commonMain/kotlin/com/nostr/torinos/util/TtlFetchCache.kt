package com.nostr.torinos.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * リンクプレビューやOEmbedタイトルのような、通信結果をevent単位ではなくキー単位で
 * 短期間キャッシュする小規模メタデータキャッシュの共通実装。
 *
 * - 読み出しは[BoundedLruCache]経由でLRU順を更新する。
 * - 成功結果(非null)と失敗結果(null)で異なるTTLを使う。失敗は短いTTLで速やかに再試行できる
 *   ようにしつつ、直後の連続失敗で通信を叩き続けないようにする。
 * - 同一キーへの並行呼び出しは[inFlight]で1本の通信に統合する。
 * - `forceRefresh`は既存の成功キャッシュを先に消さない。新しい通信が失敗した場合、既存の
 *   成功値を保持したまま失敗用の短いTTLだけを設定し直す。呼び出し元にはその既存値を返す。
 */
internal class TtlFetchCache<K : Any, V : Any>(
    private val scope: CoroutineScope,
    maximumSize: Int,
    private val successTtlMillis: Long,
    private val failureTtlMillis: Long,
    /** ログ出力時にどのキャッシュかを区別するためのラベル(動作確認用)。 */
    private val name: String = "TtlFetchCache",
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private class Entry<V>(val value: V?, val expiresAt: Long)

    private val cacheMutex = Mutex()
    private val cache = BoundedLruCache<K, Entry<V>>(maximumSize)
    private val inFlight = mutableMapOf<K, Deferred<V?>>()

    suspend fun get(key: K, forceRefresh: Boolean = false, fetch: suspend () -> V?): V? {
        val existing = cacheMutex.withLock { cache[key] }
        if (!forceRefresh && existing != null && !existing.isExpired()) {
            cacheTraceLog { "[$name] hit key=$key" }
            return existing.value
        }

        val (deferred, joined) = cacheMutex.withLock {
            val inflightDeferred = inFlight[key]
            if (inflightDeferred != null) {
                inflightDeferred to true
            } else {
                val started = scope.async { runFetch(key, existing, fetch) }
                inFlight[key] = started
                started to false
            }
        }
        cacheTraceLog {
            if (joined) "[$name] miss key=$key joined in-flight fetch" else "[$name] miss key=$key fetching"
        }
        return deferred.await()
    }

    private suspend fun runFetch(key: K, existing: Entry<V>?, fetch: suspend () -> V?): V? {
        try {
            val value = fetch()
            cacheMutex.withLock {
                cache[key] = if (value != null) {
                    Entry(value, now() + successTtlMillis)
                } else {
                    // 既存の成功値があれば壊さず保持しつつ、再試行までの間隔だけ短いTTLで
                    // 更新する。更新しないと、次のget()が即座に再フェッチを繰り返してしまう。
                    Entry(existing?.value, now() + failureTtlMillis)
                }
            }
            cacheTraceLog { "[$name] fetched key=$key success=${value != null} size=${cache.size}" }
            return value ?: existing?.value
        } finally {
            cacheMutex.withLock { inFlight.remove(key) }
        }
    }

    private fun Entry<V>.isExpired(): Boolean = now() >= expiresAt
}
