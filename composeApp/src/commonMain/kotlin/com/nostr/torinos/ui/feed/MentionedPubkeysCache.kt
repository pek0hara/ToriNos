package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.extractNpubReferences

/**
 * 投稿本文中のnpub参照(メンション)から解決したpubkey集合を、event ID単位でキャッシュする。
 * Nostrイベントのcontentはevent ID確定後に変化しないため、[FeedItemMapper]と同じ理由・同じ形で
 * event IDだけをキーにしたLRUキャッシュとして扱える。正規表現によるマッチングとbech32デコードは
 * [FeedController.computeUpdatedFeedState]の呼び出しごと(新着投稿の受信ごとに約150ms間隔)に
 * 同じイベントへ繰り返し行われていたため、これをキャッシュして再計算を避ける。
 *
 * [mentionedPubkeys]は[FeedController]の`updateEventsMutex`配下(`Dispatchers.Default`上)から
 * 直列に呼び出される前提のため、内部で排他制御は行わない。
 */
internal class MentionedPubkeysCache(
    private val maxEntries: Int = FeedController.MAX_TIMELINE_EVENTS + 200,
) {
    private val cache = LinkedHashMap<String, Set<String>>()

    fun mentionedPubkeys(event: NostrEvent): Set<String> {
        val cached = cache.remove(event.id)
        val result = cached ?: extractNpubReferences(event.content).mapTo(mutableSetOf()) { it.pubkey }
        cache[event.id] = result
        while (cache.size > maxEntries) {
            cache.remove(cache.keys.first())
        }
        return result
    }
}
