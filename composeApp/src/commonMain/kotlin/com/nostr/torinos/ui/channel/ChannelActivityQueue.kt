package com.nostr.torinos.ui.channel

/** リレーごとの一覧で使用する補完キュー。待機中の優先順位は表示範囲に追従する。 */
internal class ChannelActivityQueue(private val concurrency: Int = 3) {
    private val pending = linkedSetOf<String>()
    private val requested = mutableSetOf<String>()
    private val active = mutableSetOf<String>()
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

    fun takeNext(): String? {
        if (stopped || active.size >= concurrency) return null
        val id = pending.firstOrNull { it in visible }
            ?: pending.firstOrNull { it in favorites }
            ?: pending.firstOrNull()
            ?: return null
        pending.remove(id)
        requested.add(id)
        active.add(id)
        return id
    }

    fun complete(id: String) { active.remove(id) }

    fun stop() {
        stopped = true
        pending.clear()
    }
}
