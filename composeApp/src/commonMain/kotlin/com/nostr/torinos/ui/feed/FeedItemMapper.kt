package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.ui.components.ParsedNoteContent
import com.nostr.torinos.ui.components.parseNoteContent

/**
 * 投稿本文の解析結果を event ID 単位でキャッシュする。Nostr イベントの content / tags は
 * ID 確定後に変化しないため、event ID だけをキーにした LRU キャッシュとして扱える。
 *
 * [map] は [FeedController] の `updateEventsMutex` 配下（`Dispatchers.Default` 上）から
 * 直列に呼び出される前提のため、内部で排他制御は行わない。
 */
internal class FeedItemMapper(
    // FeedController.MAX_TIMELINE_EVENTS（表示され得る最大件数）から導出し、下回らないことを
    // コンパイル時に保証する。定数を独立して指定すると、片方だけ変更されたときに検知できず、
    // 可視件数がこれを超えるたびに全件退避される事故につながる。
    private val maxEntries: Int = FeedController.MAX_TIMELINE_EVENTS + 200,
) {
    private val cache = LinkedHashMap<String, ParsedNoteContent>()

    fun map(events: List<NostrEvent>): Map<String, ParsedNoteContent> {
        val result = LinkedHashMap<String, ParsedNoteContent>(events.size)
        // events は新しい順。古い順に触れることで、最終的に新しい投稿ほど
        // cache内の「最近使われた」側（末尾）に残り、evictはoldest-first（先頭）から働く。
        // 逆順にすると新しい投稿から追い出される「逆LRU」になってしまう。
        for (event in events.asReversed()) {
            val parsed = cache.remove(event.id) ?: parseNoteContent(event)
            cache[event.id] = parsed
            result[event.id] = parsed
        }
        while (cache.size > maxEntries) {
            cache.remove(cache.keys.first())
        }
        return result
    }
}
