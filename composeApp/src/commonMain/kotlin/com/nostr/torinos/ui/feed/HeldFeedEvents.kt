package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent

/**
 * フィードが保持している投稿（表示待ち・表示範囲外・除外中を含む）。
 *
 * 投稿本体と、付随情報を残す範囲を決める [HeldEventIndex]、表示待ちの判定に使う
 * 「除外されていない投稿の、並べる基準の時刻の最大値」をまとめて持ち、出し入れのたびに
 * 一緒に更新する。除外の条件（ミュート・NG ワード）が変わったときだけ [invalidateRevealable] を呼ぶ。
 */
internal class HeldFeedEvents(
    private val maxSize: Int,
    private val sortTimeOf: (NostrEvent) -> Long,
) {
    private val events = linkedMapOf<String, NostrEvent>()
    private val index = HeldEventIndex()
    private var revealableMax: Long? = null
    private var revealableStale = false

    val values: Collection<NostrEvent> get() = events.values

    operator fun get(id: String): NostrEvent? = events[id]

    /**
     * 投稿を加え、上限を超えた分を古く入った順に捨てる。捨てた投稿は返すので、呼び出し側は
     * 並べる基準の時刻など自分の持つ情報を片付けること（その前にこのクラスが参照する）。
     */
    fun add(event: NostrEvent, references: List<String>): List<NostrEvent> {
        events[event.id] = event
        index.add(event.id, references)
        val evicted = mutableListOf<NostrEvent>()
        while (events.size > maxSize) {
            val oldestId = events.keys.first()
            events.remove(oldestId)?.let { removed ->
                forget(removed)
                evicted += removed
            }
        }
        return evicted
    }

    fun remove(id: String) {
        events.remove(id)?.let(::forget)
    }

    fun clear() {
        events.clear()
        index.clear()
        revealableMax = null
        revealableStale = false
    }

    /** 除外されていない投稿が加わった、または並べる基準の時刻が新しくなった。 */
    fun noteRevealable(sortTime: Long) {
        if (revealableStale) return
        revealableMax = maxOf(revealableMax ?: sortTime, sortTime)
    }

    /** ミュートや NG ワードが変わり、除外される投稿が入れ替わった。 */
    fun invalidateRevealable() {
        revealableStale = true
    }

    /** 除外されていない投稿の、並べる基準の時刻の最大値。必要なときだけ全件から求め直す。 */
    fun newestRevealable(isFiltered: (NostrEvent) -> Boolean): Long? {
        if (revealableStale) {
            revealableStale = false
            revealableMax = events.values.filter { !isFiltered(it) }.maxOfOrNull(sortTimeOf)
        }
        return revealableMax
    }

    fun snapshot(): HeldEventIndex.Snapshot = index.snapshot()

    fun remember(snapshot: HeldEventIndex.Snapshot, resolved: Set<String>) = index.remember(snapshot, resolved)

    private fun forget(event: NostrEvent) {
        index.remove(event.id)
        // 最大値の投稿が抜けたときだけ求め直す。
        val max = revealableMax
        if (max != null && sortTimeOf(event) >= max) revealableStale = true
    }
}
