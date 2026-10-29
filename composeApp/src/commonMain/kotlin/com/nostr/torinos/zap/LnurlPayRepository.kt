package com.nostr.torinos.zap

import com.nostr.torinos.createHttpClient
import com.nostr.torinos.util.BoundedLruCache
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock

/**
 * 受け取り先の URL ごとの LNURL-pay 応答。同じ URL への同時取得は1回にまとめ、待っていた側にも同じ結果を返す。
 * 成功は [ttlMillis] のあいだ覚える。失敗は覚えないが、同じ URL への取得は [retryIntervalMillis] に1回までにする
 * （連打や、レシートが続けて届いたときに相手のサーバーへ繰り返し送らないため）。
 */
internal class LnurlPayRepository(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> String,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ttlMillis: Long = 30 * 60 * 1_000L,
    private val retryIntervalMillis: Long = 60 * 1_000L,
    maxEntries: Int = 1_000,
) {
    private data class Cached(val endpoint: PayEndpoint, val fetchedAt: Long)
    private data class Failure(val error: ZapException, val at: Long)

    private val mutex = Mutex()
    private val cache = BoundedLruCache<String, Cached>(maxEntries)
    private val failures = BoundedLruCache<String, Failure>(maxEntries)
    private val inFlight = mutableMapOf<String, Deferred<Result<PayEndpoint>>>()

    /** キャッシュにあれば取りに行かずに返す。 */
    suspend fun cached(url: String): PayEndpoint? = mutex.withLock {
        cache[url]?.takeIf { now() - it.fetchedAt < ttlMillis }?.endpoint
    }

    suspend fun get(url: String, forceRefresh: Boolean = false): PayEndpoint {
        val deferred = mutex.withLock {
            inFlight[url]?.let { return@withLock it }
            val current = now()
            if (!forceRefresh) cache[url]?.takeIf { current - it.fetchedAt < ttlMillis }?.let { return it.endpoint }
            failures[url]?.takeIf { current - it.at < retryIntervalMillis }?.let { throw it.error }
            scope.async {
                try {
                    val endpoint = try {
                        parsePayEndpoint(url, fetch(url))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ZapException) {
                        throw e
                    } catch (e: Exception) {
                        throw ZapException(UNREACHABLE_MESSAGE)
                    }
                    mutex.withLock {
                        cache[url] = Cached(endpoint, now())
                        failures.remove(url)
                    }
                    Result.success(endpoint)
                } catch (e: ZapException) {
                    mutex.withLock { failures[url] = Failure(e, now()) }
                    // 失敗を Deferred の外へ投げると、スコープによっては共有のスコープごと止まるため値で渡す。
                    Result.failure(e)
                } finally {
                    // 取り消されても外す。残ると、以後の取得が取り消し済みの結果を待ち続ける。
                    withContext(NonCancellable) { mutex.withLock { inFlight.remove(url) } }
                }
            }.also { inFlight[url] = it }
        }
        return deferred.await().getOrThrow()
    }
}

/** 本文を [maxBytes] バイトまで読む。超えたら、それ以上は読まずに null。 */
internal suspend fun ByteReadChannel.readTextAtMost(maxBytes: Int): String? {
    val bytes = readRemaining(maxBytes + 1L).readByteArray()
    return if (bytes.size > maxBytes) null else bytes.decodeToString()
}

/**
 * LNURL の HTTP。https だけを使い、リダイレクトも https のみ追う。10 秒で打ち切り、応答は 64KB まで読む。
 */
internal object LnurlHttp {
    private sealed interface Fetched
    private class Redirect(val location: String) : Fetched
    private class Body(val text: String) : Fetched

    private const val TIMEOUT_MS = 10_000L
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val MAX_REDIRECTS = 3
    private val client by lazy { createHttpClient().config { followRedirects = false } }

    suspend fun get(url: String): String = try {
        withTimeout(TIMEOUT_MS) {
            var current = url
            repeat(MAX_REDIRECTS + 1) {
                if (!current.startsWith("https://")) throw ZapException(UNREACHABLE_MESSAGE)
                // execute の中で本文を流しながら読む（get だと本文を全部受け取ってから返るため、上限が効かない）。
                val result = client.prepareGet(current).execute { response ->
                    if (response.status.value in 300..399) {
                        val location = response.headers[HttpHeaders.Location] ?: throw ZapException(UNREACHABLE_MESSAGE)
                        return@execute Redirect(URLBuilder(current).takeFrom(location).buildString())
                    }
                    val body = response.bodyAsChannel().readTextAtMost(MAX_BODY_BYTES)
                        ?: throw ZapException(UNREACHABLE_MESSAGE)
                    // エラーの JSON（status: ERROR）は 4xx で返ることがあるので、本文があれば読ませる。
                    if (!response.status.isSuccess() && !body.trimStart().startsWith("{")) {
                        throw ZapException(UNREACHABLE_MESSAGE)
                    }
                    Body(body)
                }
                when (result) {
                    is Redirect -> current = result.location
                    is Body -> return@withTimeout result.text
                }
            }
            throw ZapException(UNREACHABLE_MESSAGE)
        }
    } catch (e: TimeoutCancellationException) {
        throw ZapException(UNREACHABLE_MESSAGE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ZapException) {
        throw e
    } catch (e: Exception) {
        throw ZapException(UNREACHABLE_MESSAGE)
    }

    /** アプリ全体で共有する LNURL-pay 応答のキャッシュ。 */
    val endpoints by lazy {
        LnurlPayRepository(CoroutineScope(SupervisorJob() + Dispatchers.Default), ::get)
    }
}
