package com.nostr.torinos.network

import com.nostr.torinos.model.RelayMessage
import com.nostr.torinos.util.appLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * フィード読み込みの冗長性調査用の一時計測。
 * WebSocket に実際に出入りする REQ / EVENT をサブスクリプション種別ごとに集計し、
 * 一定間隔でまとめて出力する。既定は無効。調査時だけ ENABLED を true にする(不要になれば NostrRelay の呼び出しごと削除)。
 */
internal object TrafficMetrics {
    private const val ENABLED = false
    private const val DUMP_INTERVAL_MS = 10_000L
    private const val TAG = "[Traffic]"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val stats = linkedMapOf<String, Stat>()
    private val seenByCategory = mutableMapOf<String, HashSet<String>>()
    private val seenGlobal = HashSet<String>()
    private var startedAtMs = 0L
    private var dumpJob: Job? = null
    private var dirty = false

    private class Stat {
        var req = 0
        var reqBytes = 0L
        var close = 0
        var events = 0
        var uniqueInCategory = 0
        var newGlobally = 0
        var eventBytes = 0L
        var eose = 0
        var closed = 0
        val eventsByRelay = linkedMapOf<String, Int>()
    }

    // 計測の失敗で中継の送受信を壊さない。
    private suspend inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    suspend fun onSend(relayUrl: String, subscriptionId: String?, text: String) {
        if (!ENABLED) return
        guarded { recordSend(relayUrl, subscriptionId, text) }
    }

    suspend fun onReceive(relayUrl: String, message: RelayMessage, bytes: Int) {
        if (!ENABLED) return
        guarded { recordReceive(relayUrl, message, bytes) }
    }

    private suspend fun recordSend(relayUrl: String, subscriptionId: String?, text: String) {
        val type = text.substringAfter('[').substringBefore(',').trim('"')
        if (type != "REQ" && type != "CLOSE") return
        val subscriptionId = subscriptionId ?: return
        val category = categoryOf(subscriptionId)
        var detail = ""
        if (type == "REQ") {
            detail = runCatching { describeFilters(json.parseToJsonElement(text).jsonArray) }.getOrDefault("?")
        }
        mutex.withLock {
            ensureStartedLocked()
            val stat = stats.getOrPut(category) { Stat() }
            if (type == "REQ") {
                stat.req++
                stat.reqBytes += text.length
            } else {
                stat.close++
            }
            dirty = true
        }
        if (type == "REQ") {
            appLog("$TAG REQ cat=$category sub=$subscriptionId relay=${hostOf(relayUrl)} bytes=${text.length} $detail")
        }
    }

    private suspend fun recordReceive(relayUrl: String, message: RelayMessage, bytes: Int) {
        val subscriptionId = when (message) {
            is RelayMessage.Event -> message.subscriptionId
            is RelayMessage.EndOfStoredEvents -> message.subscriptionId
            is RelayMessage.Closed -> message.subscriptionId
            else -> return
        }
        val category = categoryOf(subscriptionId)
        mutex.withLock {
            ensureStartedLocked()
            val stat = stats.getOrPut(category) { Stat() }
            when (message) {
                is RelayMessage.Event -> {
                    stat.events++
                    stat.eventBytes += bytes
                    val host = hostOf(relayUrl)
                    stat.eventsByRelay[host] = (stat.eventsByRelay[host] ?: 0) + 1
                    val id = message.event.id
                    if (seenByCategory.getOrPut(category) { HashSet() }.add(id)) stat.uniqueInCategory++
                    if (seenGlobal.add(id)) stat.newGlobally++
                }
                is RelayMessage.EndOfStoredEvents -> stat.eose++
                is RelayMessage.Closed -> stat.closed++
                else -> Unit
            }
            dirty = true
        }
    }

    /** 購読IDから数字部分(インスタンス・世代・連番)を除いた種別名を得る。 */
    private fun categoryOf(subscriptionId: String): String {
        val head = subscriptionId.substringBefore('-')
        return when {
            "-history-" in subscriptionId -> "$head-history"
            subscriptionId.startsWith("profile-") -> subscriptionId.split('-').take(2).joinToString("-")
            subscriptionId.startsWith("event-by-id") -> "event-by-id"
            else -> head
        }
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

    private fun describeFilters(request: JsonArray): String {
        val filters = request.drop(2).map { element ->
            val obj = element as? JsonObject ?: return@map "?"
            obj.entries.joinToString(",") { (key, value) ->
                if (value is JsonArray) "$key[${value.size}]" else "$key=$value"
            }
        }
        return "filters=${filters.size} " + filters.joinToString(" | ", prefix = "{", postfix = "}").take(220)
    }

    private fun ensureStartedLocked() {
        if (dumpJob != null) return
        startedAtMs = Clock.System.now().toEpochMilliseconds()
        dumpJob = scope.launch {
            while (true) {
                delay(DUMP_INTERVAL_MS)
                dump()
            }
        }
    }

    private suspend fun dump() {
        val lines = mutex.withLock {
            if (!dirty) return
            dirty = false
            val elapsed = (Clock.System.now().toEpochMilliseconds() - startedAtMs) / 1000
            buildList {
                add("$TAG ==== +${elapsed}s (cumulative) ====")
                var totalReq = 0
                var totalEvents = 0
                var totalNew = 0
                var totalBytes = 0L
                stats.forEach { (category, stat) ->
                    totalReq += stat.req
                    totalEvents += stat.events
                    totalNew += stat.newGlobally
                    totalBytes += stat.eventBytes
                    val dupInCategory = pct(stat.events - stat.uniqueInCategory, stat.events)
                    val redundant = pct(stat.events - stat.newGlobally, stat.events)
                    add(
                        "$TAG $category req=${stat.req} reqB=${stat.reqBytes} close=${stat.close} | " +
                            "ev=${stat.events} evB=${stat.eventBytes} eose=${stat.eose} closed=${stat.closed} " +
                            "dupInCat=$dupInCategory redundantVsAll=$redundant relays=${stat.eventsByRelay}",
                    )
                }
                add(
                    "$TAG TOTAL req=$totalReq ev=$totalEvents evB=$totalBytes " +
                        "uniqueEventIds=${seenGlobal.size} redundantEvents=${totalEvents - totalNew} " +
                        "(${pct(totalEvents - totalNew, totalEvents)})",
                )
            }
        }
        lines.forEach(::appLog)
    }

    private fun pct(part: Int, total: Int): String =
        if (total == 0) "0%" else "${part * 100 / total}%"
}
