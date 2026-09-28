package com.nostr.torinos.util

/**
 * 小さなプロセスメモリ用キャッシュ。
 *
 * 呼び出し元の dispatcher / Mutex で直列化して使う。読み出し時にも利用順を更新し、
 * [maximumSize] を超えた場合は最後に使われてから最も時間が経った要素を破棄する。
 */
internal class BoundedLruCache<K, V>(
    private val maximumSize: Int,
) {
    private val entries = LinkedHashMap<K, V>()

    init {
        require(maximumSize > 0) { "maximumSize must be positive" }
    }

    operator fun get(key: K): V? =
        entries.remove(key)?.also { value -> entries[key] = value }

    operator fun set(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
        while (entries.size > maximumSize) {
            entries.remove(entries.keys.first())
        }
    }

    fun remove(key: K): V? = entries.remove(key)

    fun clear() {
        entries.clear()
    }

    internal val size: Int get() = entries.size
}
