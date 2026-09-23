package com.nostr.torinos.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TtlFetchCacheTest {
    @Test
    fun cachesSuccessUntilTtlExpires() = runTest {
        var now = 0L
        var fetchCount = 0
        val cache = TtlFetchCache<String, String>(
            scope = this,
            maximumSize = 10,
            successTtlMillis = 100,
            failureTtlMillis = 10,
            now = { now },
        )
        val fetch: suspend () -> String = { fetchCount++; "value" }

        assertEquals("value", cache.get("k", fetch = fetch))
        assertEquals("value", cache.get("k", fetch = fetch))
        assertEquals(1, fetchCount)

        now = 101
        assertEquals("value", cache.get("k", fetch = fetch))
        assertEquals(2, fetchCount)
    }

    @Test
    fun cachesFailureShorterThanSuccess() = runTest {
        var now = 0L
        var fetchCount = 0
        val cache = TtlFetchCache<String, String>(
            scope = this,
            maximumSize = 10,
            successTtlMillis = 100,
            failureTtlMillis = 10,
            now = { now },
        )
        val fetch: suspend () -> String? = { fetchCount++; null }

        assertNull(cache.get("k", fetch = fetch))
        assertNull(cache.get("k", fetch = fetch))
        assertEquals(1, fetchCount)

        now = 11
        assertNull(cache.get("k", fetch = fetch))
        assertEquals(2, fetchCount)
    }

    @Test
    fun forceRefreshFailureKeepsExistingSuccessValue() = runTest {
        var now = 0L
        var succeed = true
        val cache = TtlFetchCache<String, String>(
            scope = this,
            maximumSize = 10,
            successTtlMillis = 100,
            failureTtlMillis = 10,
            now = { now },
        )
        assertEquals("good", cache.get("k") { "good" })

        succeed = false
        val result = cache.get("k", forceRefresh = true) { if (succeed) "good" else null }

        assertEquals("good", result)
        // 失敗後も既存の成功値が読み出せる(forceRefreshで壊れていない)。
        now = 5
        assertEquals("good", cache.get("k") { error("should not refetch before failure TTL") })
    }

    @Test
    fun concurrentGetsForSameKeyShareOneFetch() = runTest {
        var fetchCount = 0
        val gate = CompletableDeferred<String>()
        val cache = TtlFetchCache<String, String>(
            scope = this,
            maximumSize = 10,
            successTtlMillis = 1_000,
            failureTtlMillis = 100,
        )
        val fetch: suspend () -> String = { fetchCount++; gate.await() }

        val first = async { cache.get("k", fetch = fetch) }
        val second = async { cache.get("k", fetch = fetch) }
        runCurrent()
        assertEquals(1, fetchCount)

        gate.complete("shared")
        assertEquals("shared", first.await())
        assertEquals("shared", second.await())
    }

    @Test
    fun exceptionDuringFetchStillClearsInFlightEntry() = runTest {
        var attempt = 0
        // 本番の各リポジトリはSupervisorJobのfetchScopeを使う。1件の失敗が他へ波及しないことを
        // 前提にした構成のため、テストでもSupervisorJobの子スコープを使う。
        val cache = TtlFetchCache<String, String>(
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
            maximumSize = 10,
            successTtlMillis = 1_000,
            failureTtlMillis = 100,
        )

        val threw = runCatching {
            cache.get("k") {
                attempt++
                error("boom")
            }
        }.isFailure
        assertEquals(true, threw)

        // in-flightエントリーがfinallyで解放されていれば、次のget()は新しいフェッチを開始できる。
        // 解放されていなければ、このget()は失敗した1回目のDeferredを待ち続けて例外を再送出する。
        assertEquals("retried", cache.get("k") { attempt++; "retried" })
        assertEquals(2, attempt)
    }
}
