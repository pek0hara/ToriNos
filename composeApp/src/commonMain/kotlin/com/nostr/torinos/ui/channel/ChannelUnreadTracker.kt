package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent

/** 一覧の未読表示。[hasNewActivity]は未開封チャンネル向けの「新着あり」(第16.12.4節)。 */
internal data class ChannelUnreadBadge(
    val count: Int = 0,
    val isLowerBound: Boolean = false,
    val hasNewActivity: Boolean = false,
)

/** 起動時キャッチアップの1回分の問い合わせ(第16.12.1節)。 */
internal data class ChannelUnreadCatchUpChunk(
    val channelIds: List<String>,
    val since: Long,
)

internal data class ChannelUnreadCatchUpResult(
    val count: Int,
    val isLowerBound: Boolean,
    /** 集計時点の lastReadAt。完了前に既読化されたチャンネルの結果を捨てるために使う。 */
    val lastReadAtSnapshot: Long,
)

internal object ChannelUnreadCatchUp {
    const val CHUNK_SIZE = 200
    const val CATCH_UP_LIMIT = 1_000

    /**
     * NIP-11 の max_limit が不明なときに仮定するリレー側の上限。strfry 等の既定値に合わせる。
     * 要求 limit がリレー上限を超えると、黙って切り詰められて「上限到達」を検出できないため。
     */
    const val ASSUMED_RELAY_MAX_LIMIT = 500

    /** 上限到達チャンクの分割再問い合わせを含めた、1回のキャッチアップでの REQ 数の上限。 */
    const val MAX_REQUESTS = 16

    /**
     * 上限に達したチャンクを半分に分け、それぞれ自分の最小 lastReadAt を since にして問い合わせ直す。
     * 既読済みの古いメッセージが多いチャンネルと同じチャンクに入ったことによる取りこぼしを解消する。
     * 1チャンネルだけのチャンクは since がそのチャンネルの既読位置なので、分割せず下限値として扱う。
     */
    fun split(chunk: ChannelUnreadCatchUpChunk, lastReadAts: Map<String, Long>): List<ChannelUnreadCatchUpChunk> {
        if (chunk.channelIds.size <= 1) return emptyList()
        val half = (chunk.channelIds.size + 1) / 2
        return chunk.channelIds.chunked(half).map { ids ->
            ChannelUnreadCatchUpChunk(ids, since = ids.minOf { lastReadAts.getValue(it) })
        }
    }

    fun effectiveLimit(relayMaxLimit: Int?): Int =
        (relayMaxLimit?.takeIf { it > 0 } ?: ASSUMED_RELAY_MAX_LIMIT).coerceAtMost(CATCH_UP_LIMIT)

    /**
     * 既読位置が近いチャンネル同士を同じチャンクへ入れ、チャンクごとの since を小さく保つ。
     * 仕様の「全体の最小値」より各チャンクの取得範囲が狭くなるだけで、取りこぼしは増えない。
     */
    fun plan(lastReadAts: Map<String, Long>, chunkSize: Int = CHUNK_SIZE): List<ChannelUnreadCatchUpChunk> =
        lastReadAts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .chunked(chunkSize.coerceAtLeast(1))
            .map { entries ->
                ChannelUnreadCatchUpChunk(
                    channelIds = entries.map { it.key },
                    since = entries.minOf { it.value },
                )
            }

    /**
     * チャンクの返却イベントを channelId ごとに数える。返却件数が [limit] に達したチャンクは
     * 取りこぼしうるため、含まれる全チャンネルを下限値扱いにする。
     */
    fun tally(
        chunk: ChannelUnreadCatchUpChunk,
        events: Collection<NostrEvent>,
        lastReadAts: Map<String, Long>,
        limit: Int = CATCH_UP_LIMIT,
        channelIdOf: (NostrEvent) -> String?,
    ): Map<String, ChannelUnreadCatchUpResult> {
        val targets = chunk.channelIds.toSet()
        val distinct = events.distinctBy { it.id }
        val saturated = distinct.size >= limit
        val counts = mutableMapOf<String, Int>()
        distinct.forEach { event ->
            val channelId = channelIdOf(event)?.takeIf { it in targets } ?: return@forEach
            val lastReadAt = lastReadAts[channelId] ?: return@forEach
            if (event.createdAt > lastReadAt) counts[channelId] = (counts[channelId] ?: 0) + 1
        }
        return chunk.channelIds.mapNotNull { channelId ->
            val lastReadAt = lastReadAts[channelId] ?: return@mapNotNull null
            channelId to ChannelUnreadCatchUpResult(
                count = counts[channelId] ?: 0,
                isLowerBound = saturated,
                lastReadAtSnapshot = lastReadAt,
            )
        }.toMap()
    }
}

/**
 * 未読件数をメモリ上だけで保持する(第16.12.1〜16.12.3節)。件数は永続化しない。
 *
 * 起動時キャッチアップの集計値と、セッション中にライブ受信した event の集合を分けて持つ。
 * キャッチアップは `until = ライブ購読開始時刻 - 1` で問い合わせるため、両者は時刻で重ならない。
 */
internal class ChannelUnreadTracker(
    private val maxLiveEventsPerChannel: Int = MAX_LIVE_EVENTS_PER_CHANNEL,
) {
    private val catchUp = mutableMapOf<String, ChannelUnreadCatchUpResult>()
    private val liveEvents = mutableMapOf<String, LinkedHashMap<String, Long>>()

    fun applyCatchUp(results: Map<String, ChannelUnreadCatchUpResult>, currentLastReadAts: Map<String, Long?>) {
        results.forEach { (channelId, result) ->
            // 集計中に既読化された場合は古い集計を捨て、既読化による0件を優先する。
            if (currentLastReadAts[channelId] == result.lastReadAtSnapshot) catchUp[channelId] = result
        }
    }

    /** ライブ受信した kind 42 を記録する。重複 event は1件として数える。 */
    fun onLive(channelId: String, eventId: String, createdAt: Long): Boolean {
        val events = liveEvents.getOrPut(channelId) { linkedMapOf() }
        if (events.containsKey(eventId)) return false
        events[eventId] = createdAt
        while (events.size > maxLiveEventsPerChannel) events.remove(events.keys.first())
        return true
    }

    fun badge(channelId: String, lastReadAt: Long?): ChannelUnreadBadge {
        val live = liveEvents[channelId].orEmpty()
        if (lastReadAt == null) return ChannelUnreadBadge(hasNewActivity = live.isNotEmpty())
        val base = catchUp[channelId]?.takeIf { it.lastReadAtSnapshot == lastReadAt }
        val liveCount = live.values.count { it > lastReadAt }
        return ChannelUnreadBadge(
            count = (base?.count ?: 0) + liveCount,
            isLowerBound = base?.isLowerBound == true || live.size >= maxLiveEventsPerChannel,
        )
    }

    companion object {
        const val MAX_LIVE_EVENTS_PER_CHANNEL = 500
    }
}
