package com.nostr.torinos.journal

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.datetime.LocalDate

/**
 * 1ページで取り切れた日と、その日のイベント。
 * [allRelaysFinished]は、ここまでのページをすべてのリレーが取り終えたか（取得結果で置き換えてよいか）。
 */
internal data class JournalPageOutcome(
    val closedDates: List<LocalDate>,
    val events: List<NostrEvent>,
    val allRelaysFinished: Boolean = true,
)

/**
 * 範囲の取得を、件数の上限に届くたびに`until`を下げて繰り返す。
 * 取り切れた日（0:00 が打ち切りの時刻より後の日）だけを返し、打ち切りの時刻を含む日は次のページまで保留する。
 * 更新では取り切れた日の既存の行を消してから足すため、日の途中までのイベントを先に返してはいけない。
 */
internal class JournalRangePager(
    private val request: JournalFetchRequest,
    private val clock: JournalClock,
    private val maxPages: Int = MAX_PAGES,
) {
    private var pendingDates: List<LocalDate> = request.dates
    private val pendingEvents = linkedMapOf<String, NostrEvent>()
    private var until: Long? = null
    private var pages = 0
    private var finished = false
    private var allRelaysFinished = true
    private val failedRelays = linkedSetOf<String>()

    /** どれかのページで取り終えず、間を空けて取り直すリレー。 */
    val retryRelays: Set<String> get() = failedRelays

    /** 次に送るリクエスト。終わったら null。 */
    fun next(): JournalFetchRequest? {
        if (finished || pendingDates.isEmpty()) return null
        val nextUntil = until ?: return request
        return request.copy(dates = pendingDates, filters = request.filters.map { it.copy(until = nextUntil) })
    }

    fun accept(result: JournalFetchResult): JournalPageOutcome {
        pages += 1
        result.events.forEach { pendingEvents.getOrPut(it.id) { it } }
        failedRelays += result.retryRelays
        allRelaysFinished = allRelaysFinished && result.allRelaysFinished
        if (!result.complete) {
            finished = true
            return JournalPageOutcome(emptyList(), emptyList(), allRelaysFinished = false)
        }
        val cutOff = result.truncatedAt
        val closed = if (cutOff == null) pendingDates else pendingDates.filter { clock.startOfDay(it) > cutOff }
        pendingDates = pendingDates - closed.toSet()
        val closedSet = closed.toSet()
        val events = pendingEvents.values.filter { clock.dateOf(it.createdAt) in closedSet }
        events.forEach { pendingEvents.remove(it.id) }
        val pageUntil = result.request.filters.firstNotNullOfOrNull { it.until }
        when {
            cutOff == null -> finished = true
            // 同じ秒に上限以上のイベントがあると、続きを頼んでも進まない。
            pageUntil != null && cutOff >= pageUntil -> finished = true
            pages >= maxPages -> finished = true
            else -> until = cutOff
        }
        return JournalPageOutcome(closed, events, allRelaysFinished)
    }

    /** 終わった時点で取り切れなかった日のイベント。取得済みにはせず、足すだけにする。 */
    fun leftovers(): List<NostrEvent> = pendingEvents.values.toList().also { pendingEvents.clear() }

    private companion object {
        const val MAX_PAGES = 20
    }
}

/** リレーごと・フィルターごとに届いた件数と最古の時刻。 */
internal data class JournalFilterTally(
    val relayUrl: String,
    val filterIndex: Int,
    val count: Int,
    val oldest: Long,
)

/**
 * 受け取った（リレー, イベント）を、リレーとフィルターごとに数える。`limit`はフィルターごとにかかる。
 * イベントがどのフィルターに当たるかは種類で決める（1つのリクエストのフィルターは種類が重ならない）。
 */
internal fun journalFilterTallies(
    received: List<Pair<String, NostrEvent>>,
    filters: List<NostrFilter>,
): List<JournalFilterTally> =
    received
        .mapNotNull { (relayUrl, event) ->
            val index = filters.indexOfFirst { it.kinds?.contains(event.kind) != false }
            if (index < 0) null else Triple(relayUrl, index, event)
        }
        .groupBy({ (relayUrl, index, _) -> relayUrl to index }, { (_, _, event) -> event })
        .map { (key, events) ->
            JournalFilterTally(key.first, key.second, events.distinctBy { it.id }.size, events.minOf { it.createdAt })
        }

/**
 * しきい値に届いた組のうち、最古の時刻がいちばん新しいもの。そこより新しい分は全組で取り切れている。
 * どの組も届いていなければ null。
 */
internal fun journalTruncatedAt(tallies: Collection<JournalFilterTally>, threshold: (relayUrl: String) -> Int): Long? =
    tallies.filter { it.count >= threshold(it.relayUrl) }.maxOfOrNull { it.oldest }

/**
 * 打ち切られたとみなす件数。リレーの`max_limit`が分かればそれと求めた件数の小さい方。
 * 分からなければ、リレー側の既定の上限が求めた件数より小さい可能性があるため控えめにする
 * （続きが空なら1回余分に取るだけで済む）。フィードの履歴取得と同じ規則。
 */
internal fun journalPageThreshold(maxLimit: Int?, requested: Int): Int =
    if (maxLimit != null) minOf(maxLimit, requested) else minOf(UNKNOWN_LIMIT_THRESHOLD, requested)

private const val UNKNOWN_LIMIT_THRESHOLD = 100
