package com.nostr.torinos.ui.feed

/**
 * 保持中の投稿の ID と、その返信先・引用先の ID を参照数付きで持つ。
 *
 * 一覧の組み直しは、まだ表示していない投稿の付随情報を残すためにこの集合を使う。
 * 組み直しのたびに全件から作り直さないよう投稿の出入りで更新する。集合の構築は
 * [Snapshot.resolve] でバックグラウンドに任せ、出来た集合は変化がない間 [remember] で使い回す。
 */
internal class HeldEventIndex {
    private val counts = mutableMapOf<String, Int>()
    private val referencesByEvent = mutableMapOf<String, List<String>>()
    private var version = 0L
    private var cachedVersion = -1L
    private var cached: Set<String> = emptySet()

    fun add(eventId: String, references: List<String>) {
        if (eventId in referencesByEvent) return
        referencesByEvent[eventId] = references
        increment(eventId)
        references.forEach(::increment)
    }

    fun remove(eventId: String) {
        val references = referencesByEvent.remove(eventId) ?: return
        decrement(eventId)
        references.forEach(::decrement)
    }

    fun clear() {
        counts.clear()
        referencesByEvent.clear()
        version++
    }

    /** 呼び出し側のスレッドでは ID の一覧を写すだけにする。前回の集合が使えるならそれを渡す。 */
    fun snapshot(): Snapshot =
        if (cachedVersion == version) {
            Snapshot(version, cached, null)
        } else {
            Snapshot(version, null, counts.keys.toList())
        }

    /** [Snapshot.resolve] で作った集合を、その後に変化がなければ次回へ使い回す。 */
    fun remember(snapshot: Snapshot, resolved: Set<String>) {
        if (snapshot.version != version) return
        cached = resolved
        cachedVersion = version
    }

    private fun increment(id: String) {
        val count = counts[id] ?: 0
        counts[id] = count + 1
        if (count == 0) version++
    }

    private fun decrement(id: String) {
        val count = counts[id] ?: return
        if (count <= 1) {
            counts.remove(id)
            version++
        } else {
            counts[id] = count - 1
        }
    }

    class Snapshot internal constructor(
        internal val version: Long,
        private val set: Set<String>?,
        private val ids: List<String>?,
    ) {
        /** 変更されない集合を返す。重い構築はバックグラウンドから呼ぶ。 */
        fun resolve(): Set<String> = set ?: ids.orEmpty().toHashSet()
    }
}
