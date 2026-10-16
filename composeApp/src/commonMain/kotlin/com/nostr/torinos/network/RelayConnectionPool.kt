package com.nostr.torinos.network

/** 呼び出し元のstateMutex配下で使う。接続の寿命を購読の判断から分ける。 */
internal class RelayConnectionPool<Handle>(private val create: (String) -> Handle) {
    val active = mutableMapOf<String, Handle>()

    fun reconcile(desiredUrls: Set<String>, onRemove: (String) -> Unit = {}): Pair<List<Handle>, List<Handle>> {
        val removed = (active.keys - desiredUrls).mapNotNull { url ->
            onRemove(url)
            active.remove(url)
        }
        val added = (desiredUrls - active.keys).map { url -> create(url).also { active[url] = it } }
        return removed to added
    }
}
