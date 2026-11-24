package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock

/** 手元の照合で返す当たりの上限（長すぎる一覧を作らない）。 */
const val BUZZ_LOCAL_SEARCH_MAX_HITS = 300

/**
 * Buzz コミュニティの最近の発言の控え（docs/buzz-search-design.md 2.2）。手元で部分一致を探すのに使う。
 * 発言（9・40002・45001・45003）と、削除（5・9005）、編集（40003）を受け取り、削除は除き、編集は本文を差し替える。
 * 照合用の本文（平文を正規化したもの）は、変わったときだけ作り直す。
 */
class BuzzSearchCorpus(private val relayUrl: String, private val relayPubkey: String?) {
    private class Entry(val event: NostrEvent, val body: String, val normalized: String)

    private val lock = SynchronousLock()
    private val messages = LinkedHashMap<String, NostrEvent>()
    private val deletions = LinkedHashMap<String, NostrEvent>()
    private val edits = LinkedHashMap<String, NostrEvent>()
    private var entries: List<Entry>? = null
    private var edited: Map<String, String> = emptyMap()
    private var deleted: Set<String> = emptySet()
    /** 照合用の本文の控え（発言の ID → 本文と正規化したもの）。編集で本文が変わったら作り直す。 */
    private val normalizedCache = HashMap<String, Pair<String, String>>()

    /** 読み込んだ時刻（ミリ秒）。開き直したときに使い回すかどうかに使う。 */
    @kotlin.concurrent.Volatile
    var loadedAtMillis: Long = 0L

    val messageCount: Int get() = lock.withLock { messages.size }
    val oldestMessageAt: Long? get() = lock.withLock { messages.values.minOfOrNull { it.createdAt } }
    val newestMessageAt: Long? get() = lock.withLock { messages.values.maxOfOrNull { it.createdAt } }

    fun add(events: List<NostrEvent>) = lock.withLock {
        var changed = false
        for (event in events) {
            val target = when (event.kind) {
                in BUZZ_SEARCH_MESSAGE_KINDS -> messages
                5, GROUP_DELETE_EVENT_KIND -> deletions
                BUZZ_EDIT_KIND -> edits
                else -> continue
            }
            if (target.put(event.id, event) == null) changed = true
        }
        if (changed) entries = null
    }

    private fun author(event: NostrEvent): String = groupMessageAuthor(event, GroupFlavor.Buzz, relayPubkey)

    private fun targets(event: NostrEvent): List<String> =
        event.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) }

    /** 削除と編集を反映した照合用の一覧を作る。lock の中で呼ぶ。 */
    private fun built(): List<Entry> {
        entries?.let { return it }
        // 削除: kind 5 は作者本人、9005 はリレーが受け付けたもの（管理者だけが送れる）。
        val removed = HashSet<String>()
        for (deletion in deletions.values) {
            for (id in targets(deletion)) {
                val target = messages[id] ?: edits[id]
                val allowed = deletion.kind == GROUP_DELETE_EVENT_KIND ||
                    target == null || deletion.pubkey == target.pubkey || deletion.pubkey == author(target)
                if (allowed) removed += id
            }
        }
        // 編集: 作者本人の、削除されていない最新のもの。
        val latestEdit = HashMap<String, NostrEvent>()
        for (edit in edits.values) {
            if (edit.id in removed) continue
            val id = edit.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1) ?: continue
            val target = messages[id] ?: continue
            if (author(edit) != author(target)) continue
            val current = latestEdit[id]
            if (current == null || edit.createdAt > current.createdAt) latestEdit[id] = edit
        }
        edited = latestEdit.mapValues { it.value.content }
        deleted = removed
        val list = messages.values.filter { it.id !in removed }.map { event ->
            val body = edited[event.id] ?: if (event.kind == BUZZ_AGENT_MESSAGE_KIND) buzzAgentBody(event.content) else event.content
            val cached = normalizedCache[event.id]?.takeIf { it.first == body }
            val normalized = cached?.second ?: buzzNormalize(buzzSearchPlainText(body)).also { normalizedCache[event.id] = body to it }
            Entry(event, body, normalized)
        }
        entries = list
        return list
    }

    /** 編集で差し替えた本文（発言の ID → 本文）。リレーの当たりにも使う。 */
    fun editedBodies(): Map<String, String> = lock.withLock { built(); edited }

    /** 削除された発言の ID。リレーの当たりから除くのに使う。 */
    fun deletedIds(): Set<String> = lock.withLock { built(); deleted }

    /** 入力に部分一致する発言。良さ → 新しい順で、[BUZZ_LOCAL_SEARCH_MAX_HITS] 件まで。 */
    fun search(query: String): List<BuzzMessageHit> {
        val parsed = parseBuzzLocalQuery(query) ?: return emptyList()
        val (matched, bodies) = lock.withLock {
            val list = built()
            list.mapNotNull { entry -> buzzLocalMatchRank(entry.normalized, parsed)?.let { entry to it } }
                .sortedWith(compareBy<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.event.createdAt }.thenBy { it.first.event.id })
                .take(BUZZ_LOCAL_SEARCH_MAX_HITS)
                .map { it.first.event } to edited
        }
        return buzzMessageHits(matched, relayUrl, relayPubkey, bodies)
    }
}
