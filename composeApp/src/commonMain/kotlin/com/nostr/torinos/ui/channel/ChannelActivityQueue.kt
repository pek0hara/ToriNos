package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrFilter

/**
 * リレーごとの一覧で使用する最新発言の補完キュー。チャンネルをまとめて取り出し、1 REQ で問い合わせる。
 * 待機中の優先順位は表示範囲に追従する。[concurrency]は同時に流すバッチの数。
 */
internal class ChannelActivityQueue(private val concurrency: Int = 2) {
    private val pending = linkedSetOf<String>()
    private val requested = mutableSetOf<String>()
    private var activeBatches = 0
    private var favorites = emptySet<String>()
    private var visible = emptySet<String>()
    private var stopped = false

    init { require(concurrency > 0) }

    fun enqueue(ids: Collection<String>) {
        if (!stopped) pending.addAll(ids.filter { it !in requested })
    }

    fun prioritize(favorites: Set<String>, visible: Set<String>) {
        this.favorites = favorites
        this.visible = visible
    }

    /** 表示中 → ★ → その他の順に最大[max]件を取り出す。同時数の上限か待機なしなら空。 */
    fun takeBatch(max: Int): List<String> {
        if (stopped || activeBatches >= concurrency || pending.isEmpty()) return emptyList()
        val batch = (pending.filter { it in visible } + pending.filter { it in favorites } + pending)
            .distinct()
            .take(max)
        pending.removeAll(batch.toSet())
        requested.addAll(batch)
        activeBatches++
        return batch
    }

    /** [takeBatch]で取り出したバッチの完了。 */
    fun complete(batch: Collection<String>) {
        if (batch.isNotEmpty() && activeBatches > 0) activeBatches--
    }

    fun stop() {
        stopped = true
        pending.clear()
    }
}

/** チャンネルごとに最新の発言1件を問い合わせるフィルター。1 REQ に複数入れて送る。 */
internal fun channelActivityFilters(channelIds: List<String>, sinceOf: (String) -> Long?): List<NostrFilter> =
    channelIds.map { channelId ->
        NostrFilter(kinds = listOf(42), eTags = listOf(channelId), since = sinceOf(channelId), limit = 1)
    }
