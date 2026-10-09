package com.nostr.torinos.network

import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock

/**
 * 取得済みのリレー情報（NIP-11）の上限。通信はせず、[NostrRepository] が接続時に取得した値だけを持つ。
 */
object RelayLimits {
    private val lock = SynchronousLock()
    private val maxLimits = mutableMapOf<String, Int>()

    /** 1つのフィルターで返す最大件数（max_limit）。分からなければ null。 */
    fun maxLimit(relayUrl: String): Int? = lock.withLock { maxLimits[relayUrl] }

    fun recordMaxLimit(relayUrl: String, maxLimit: Int?) {
        lock.withLock {
            if (maxLimit != null && maxLimit > 0) maxLimits[relayUrl] = maxLimit else maxLimits.remove(relayUrl)
        }
    }

    internal fun clearForTest() = lock.withLock { maxLimits.clear() }
}
