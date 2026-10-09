package com.nostr.torinos.network

import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.fetchTraceLog
import com.nostr.torinos.util.isFetchTraceEnabled
import com.nostr.torinos.util.withLock
import kotlin.time.TimeSource

/**
 * 購読ごと・リレーごとの通信の流れ（REQ/受信件数/EOSE/CLOSED/完了）をまとめてログに出す。動作確認用。
 *
 * イベントは1件ずつ出さず、EOSE・CLOSED・購読終了の時点で件数と所要時間にまとめる。
 */
internal object FetchTrace {
    private class RelayStats(val requestedAt: TimeSource.Monotonic.ValueTimeMark) {
        var events = 0
        var duplicates = 0
        var liveEvents = 0
        var firstEventMs: Long? = null
        var eoseMs: Long? = null
    }

    private val lock = SynchronousLock()
    private val stats = HashMap<String, RelayStats>()
    private val openedAt = HashMap<String, TimeSource.Monotonic.ValueTimeMark>()

    private fun key(subscriptionId: String, relayUrl: String) = "$subscriptionId|$relayUrl"

    fun req(subscriptionId: String, relayUrl: String, filters: List<NostrFilter>) {
        if (!isFetchTraceEnabled) return
        val resent = lock.withLock {
            val previous = stats[key(subscriptionId, relayUrl)]
            stats[key(subscriptionId, relayUrl)] = RelayStats(TimeSource.Monotonic.markNow())
            openedAt.getOrPut(subscriptionId) { TimeSource.Monotonic.markNow() }
            previous != null
        }
        fetchTraceLog {
            "REQ ${if (resent) "(再送) " else ""}$subscriptionId → ${relayUrl.short()} ${filters.joinToString(" | ") { it.summary() }}"
        }
    }

    fun clientClose(subscriptionId: String, relayUrl: String) {
        if (!isFetchTraceEnabled) return
        val removed = lock.withLock { stats.remove(key(subscriptionId, relayUrl)) }
        fetchTraceLog { "CLOSE $subscriptionId → ${relayUrl.short()} ${removed?.describe() ?: "(REQ記録なし)"}" }
    }

    fun event(subscriptionId: String, relayUrl: String, firstDelivery: Boolean, live: Boolean) {
        if (!isFetchTraceEnabled) return
        lock.withLock {
            val stat = stats[key(subscriptionId, relayUrl)] ?: return@withLock
            if (live) stat.liveEvents++ else stat.events++
            if (!firstDelivery) stat.duplicates++
            if (stat.firstEventMs == null) stat.firstEventMs = stat.requestedAt.elapsedNow().inWholeMilliseconds
        }
    }

    /** 購読していない（終了済み・未知の）購読IDへの受信。CLOSE後も送ってくるリレーの検出用。 */
    fun stray(subscriptionId: String, relayUrl: String, type: String) {
        fetchTraceLog { "STRAY $type $subscriptionId ← ${relayUrl.short()}（購読外）" }
    }

    fun eose(subscriptionId: String, relayUrl: String) {
        if (!isFetchTraceEnabled) return
        val line = lock.withLock {
            val stat = stats[key(subscriptionId, relayUrl)] ?: return@withLock "(REQ記録なし)"
            stat.eoseMs = stat.requestedAt.elapsedNow().inWholeMilliseconds
            stat.describe()
        }
        fetchTraceLog { "EOSE $subscriptionId ← ${relayUrl.short()} $line" }
    }

    fun closed(subscriptionId: String, relayUrl: String, reason: String) {
        if (!isFetchTraceEnabled) return
        val line = lock.withLock { stats.remove(key(subscriptionId, relayUrl))?.describe() ?: "(REQ記録なし)" }
        fetchTraceLog { "CLOSED $subscriptionId ← ${relayUrl.short()} reason=\"$reason\" $line" }
    }

    fun fetchCompleted(subscriptionId: String, outcomes: Map<String, RelayOutcome>, timedOut: Boolean) {
        if (!isFetchTraceEnabled) return
        val elapsed = lock.withLock { openedAt.remove(subscriptionId)?.elapsedNow()?.inWholeMilliseconds }
        fetchTraceLog {
            val results = outcomes.entries.joinToString(" ") { (url, outcome) -> "${url.short()}=${outcome.label()}" }
            "COMPLETE $subscriptionId ${if (timedOut) "timedOut " else ""}${elapsed ?: "?"}ms $results"
        }
    }

    fun subscriptionEnded(subscriptionId: String) {
        if (!isFetchTraceEnabled) return
        lock.withLock { openedAt.remove(subscriptionId) }
    }

    fun cacheReplay(subscriptionId: String, count: Int) {
        if (count == 0) return
        fetchTraceLog { "CACHE $subscriptionId キャッシュから$count 件" }
    }

    fun notice(relayUrl: String, message: String) {
        fetchTraceLog { "NOTICE ${relayUrl.short()} ${message.take(200)}" }
    }

    fun connected(relayUrl: String, resyncCount: Int) {
        fetchTraceLog { "CONNECTED ${relayUrl.short()} 購読の再送 $resyncCount 件" }
    }

    fun disconnected(relayUrl: String, retryDelaySeconds: Long) {
        fetchTraceLog { "DISCONNECTED ${relayUrl.short()} ${retryDelaySeconds}s後に再接続" }
    }

    private fun RelayStats.describe(): String = buildString {
        append("events=$events")
        if (liveEvents > 0) append(" live=$liveEvents")
        if (duplicates > 0) append(" dup=$duplicates")
        firstEventMs?.let { append(" first=${it}ms") }
        eoseMs?.let { append(" eose=${it}ms") }
        append(" open=${requestedAt.elapsedNow().inWholeMilliseconds}ms")
    }

    private fun RelayOutcome.label(): String = when (this) {
        RelayOutcome.Eose -> "EOSE"
        RelayOutcome.TimedOut -> "TIMEOUT"
        is RelayOutcome.Closed -> "CLOSED(${reason.take(60)})"
        is RelayOutcome.Unavailable -> "UNAVAILABLE(${reason.take(60)})"
    }

    private fun String.short(): String = removePrefix("wss://").removePrefix("ws://").trimEnd('/')
}

/** フィルターを件数中心に1行で表す。IDの中身は出さない。 */
internal fun NostrFilter.summary(): String = buildList {
    kinds?.let { add("kinds=$it") }
    ids?.let { add("ids=${it.size}") }
    authors?.let { add("authors=${it.size}") }
    eTags?.let { add("#e=${it.size}") }
    rootEventTags?.let { add("#E=${it.size}") }
    qTags?.let { add("#q=${it.size}") }
    pTags?.let { add("#p=${it.size}") }
    aTags?.let { add("#a=${it.size}") }
    tTags?.let { add("#t=$it") }
    rootKindTags?.let { add("#K=$it") }
    since?.let { add("since=$it") }
    until?.let { add("until=$it") }
    limit?.let { add("limit=$it") }
    search?.let { add("search") }
}.joinToString(" ")
