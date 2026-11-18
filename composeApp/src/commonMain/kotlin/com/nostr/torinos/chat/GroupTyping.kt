package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent

/** Buzz の入力中（保存されない）。docs/buzz-typing-indicator-design.md */
const val BUZZ_TYPING_KIND = 20002

/** 入力中の表示を続ける長さ（受け取ってから）。 */
internal const val TYPING_LIFETIME_MILLIS = 8_000L

/** メッセージが届いた後、その人の入力中を出さない長さ。 */
internal const val TYPING_QUIET_AFTER_MESSAGE_MILLIS = 2_000L

/** 入力中を送る最短の間隔。 */
internal const val TYPING_SEND_INTERVAL_MILLIS = 3_000L

/** 受け取った時刻と `created_at` がこれ以上ずれた入力中は捨てる。 */
private const val TYPING_MAX_SKEW_SECONDS = 30L

private val TYPING_EVENT_ID = Regex("^[0-9a-f]{64}$")
private val TYPING_CHANNEL_ID = Regex("^[a-zA-Z0-9_-]{1,128}$")

/** 入力中の単位。[threadRootId] が null ならチャンネル直下。 */
data class TypingScope(val threadRootId: String?)

/**
 * 入力中のイベント。content は空（下書きの中身は送らない）。スレッドの中ではルートへの `reply` を付ける
 * （Buzz のアプリの `typing.send` と同じ形）。
 */
fun buildBuzzTyping(groupId: String, threadRootId: String?): GroupEventDraft =
    GroupEventDraft(
        BUZZ_TYPING_KIND,
        listOf(listOf("h", groupId)) + (threadRootId?.let { listOf(listOf("e", it, "", "reply")) } ?: emptyList()),
        "",
    )

/**
 * [groupId] への入力中なら、その単位。形が崩れたもの（`h` が1つでない、`e` が3つ以上、`reply` がちょうど1つでない、
 * `root` が2つ以上、ID が不正、content が空でない）は null。Buzz の `typing.ts` と同じ判定。
 */
fun buzzTypingScope(event: NostrEvent, groupId: String): TypingScope? {
    if (event.kind != BUZZ_TYPING_KIND || event.content.isNotEmpty()) return null
    val hTags = event.tags.filter { it.firstOrNull() == "h" }
    if (hTags.size != 1 || hTags.single().getOrNull(1) != groupId || !TYPING_CHANNEL_ID.matches(groupId)) return null
    val refs = event.tags.filter { it.firstOrNull() == "e" }
    if (refs.isEmpty()) return TypingScope(null)
    if (refs.size > 2) return null
    if (refs.any { it.getOrNull(1)?.let(TYPING_EVENT_ID::matches) != true || it.getOrNull(3) !in setOf("root", "reply") }) return null
    if (refs.count { it.getOrNull(3) == "reply" } != 1 || refs.count { it.getOrNull(3) == "root" } > 1) return null
    return TypingScope(buzzThreadRootId(event))
}

/** 入力中を送る間隔を [intervalMillis] 以上あける。 */
class TypingThrottle(private val intervalMillis: Long = TYPING_SEND_INTERVAL_MILLIS) {
    private var lastSentAt: Long? = null

    /** 送ってよければ true（送ったものとして記録する）。 */
    fun tryAcquire(nowMillis: Long): Boolean {
        val last = lastSentAt
        if (last != null && nowMillis - last < intervalMillis) return false
        lastSentAt = nowMillis
        return true
    }
}

/**
 * 1つのグループ（Buzz のチャンネル）の入力中の記録。人と単位（チャンネル直下・スレッド）ごとに持つ。
 * 表示の期限は受け取った時刻から数える（保存されないイベントは配信中にしか届かないため。相手の時計のずれに左右されない）。
 */
class TypingTracker(
    private val viewer: String?,
    private val groupId: String,
    private val maxRecords: Int = 1024,
) {
    private class Record(val pubkey: String, val scope: TypingScope) {
        /** 最後の入力中・メッセージの `created_at`（秒）。 */
        var lastTypingAt = -1L
        var lastMessageAt = -1L
        var visibleUntil = 0L
        var quietUntil = 0L
        /** 入力中になった時刻（並び順）。 */
        var since = 0L
    }

    private val records = LinkedHashMap<Pair<String, TypingScope>, Record>()

    /** 入力中のイベントを受け取った。表示が変わったら true。 */
    fun receiveTyping(event: NostrEvent, nowMillis: Long): Boolean {
        if (event.pubkey == viewer) return false
        val scope = buzzTypingScope(event, groupId) ?: return false
        if (kotlin.math.abs(event.createdAt - nowMillis / 1000) > TYPING_MAX_SKEW_SECONDS) return false
        expire(nowMillis)
        val record = recordFor(event.pubkey, scope) ?: return false
        if (event.createdAt <= record.lastTypingAt || event.createdAt < record.lastMessageAt) return false
        record.lastTypingAt = event.createdAt
        // 送信直後に遅れて届いた入力中は出さない（静かにする間も、時刻は覚えて後の再送を弾く）。
        if (nowMillis < record.quietUntil) return false
        val wasVisible = record.visibleUntil > nowMillis
        if (!wasVisible) record.since = nowMillis
        record.visibleUntil = nowMillis + TYPING_LIFETIME_MILLIS
        return !wasVisible
    }

    /** メッセージ（kind 9・40002）を受け取った。その人の入力中を消す。表示が変わったら true。 */
    fun receiveMessage(event: NostrEvent, nowMillis: Long): Boolean {
        if (event.kind != GROUP_CHAT_KIND && event.kind != BUZZ_AGENT_MESSAGE_KIND) return false
        if (event.tags.none { it.firstOrNull() == "h" && it.getOrNull(1) == groupId }) return false
        expire(nowMillis)
        val root = buzzThreadRootId(event)
        // 記録が無くても作って時刻を覚える（後から遅れて届いた、メッセージより古い入力中を弾くため）。
        var changed = complete(recordFor(event.pubkey, TypingScope(root)), event.createdAt, nowMillis)
        // スレッドへの返信は、同じ人のチャンネル直下の入力中も終える（入力中とメッセージが別の単位に入ることがあるため）。
        if (root != null) changed = complete(records[event.pubkey to TypingScope(null)], event.createdAt, nowMillis) || changed
        return changed
    }

    /** [threadRootId] の単位で入力中の人（入力中になった順）。 */
    fun visible(threadRootId: String?, nowMillis: Long): List<String> =
        records.values
            .filter { it.scope.threadRootId == threadRootId && it.visibleUntil > nowMillis }
            .sortedBy { it.since }
            .map { it.pubkey }

    /** 次に表示が変わる時刻。表示中の人がいなければ null。 */
    fun nextChangeAt(nowMillis: Long): Long? =
        records.values.map { it.visibleUntil }.filter { it > nowMillis }.minOrNull()

    private fun complete(record: Record?, createdAt: Long, nowMillis: Long): Boolean {
        record ?: return false
        if (createdAt <= record.lastMessageAt) return false
        record.lastMessageAt = createdAt
        // メッセージより後に始めた入力は続いている。
        if (createdAt < record.lastTypingAt) return false
        val wasVisible = record.visibleUntil > nowMillis
        record.visibleUntil = 0
        record.quietUntil = nowMillis + TYPING_QUIET_AFTER_MESSAGE_MILLIS
        return wasVisible
    }

    private fun recordFor(pubkey: String, scope: TypingScope): Record? {
        val key = pubkey to scope
        records[key]?.let { return it }
        // 上限では新しい単位を捨てる（入力を終えた証拠を消さないため）。
        if (records.size >= maxRecords) return null
        return Record(pubkey, scope).also { records[key] = it }
    }

    /** 表示も静かな時間も過ぎ、入力中の再送も来ない頃の記録を消す。 */
    private fun expire(nowMillis: Long) {
        val iterator = records.values.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next()
            val lastSeenMillis = maxOf(record.lastTypingAt, record.lastMessageAt) * 1000
            val retainUntil = maxOf(record.visibleUntil, record.quietUntil, lastSeenMillis + TYPING_LIFETIME_MILLIS)
            if (retainUntil <= nowMillis) iterator.remove()
        }
    }
}

/** 入力中の表示の文言。誰もいなければ null。 */
fun typingLabel(names: List<String>): String? = when (names.size) {
    0 -> null
    1 -> "${names[0]}さんが入力中…"
    2 -> "${names[0]}さんと${names[1]}さんが入力中…"
    else -> "${names[0]}さんほか${names.size - 1}人が入力中…"
}
