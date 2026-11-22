package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Buzz コミュニティ内の検索（docs/buzz-search-design.md）。
 * 公式モバイル版（buzz/mobile の features/search）と同じ送り方・並べ方にする。ここは UI を持たない純粋な関数だけ。
 */

/** メッセージの検索で探す kind（公式モバイル版と同じ。リレーの索引に入るのはこれと kind 0 だけ）。 */
val BUZZ_SEARCH_MESSAGE_KINDS = listOf(GROUP_CHAT_KIND, BUZZ_AGENT_MESSAGE_KIND, BUZZ_FORUM_POST_KIND, BUZZ_FORUM_COMMENT_KIND)
const val BUZZ_SEARCH_MESSAGE_LIMIT = 20
const val BUZZ_SEARCH_CHANNEL_LIMIT = 8
const val BUZZ_SEARCH_PEOPLE_SHOWN = 8
/** 人の検索で頼む数。出す数より多めに頼む（公式は 3 倍）。 */
const val BUZZ_SEARCH_PEOPLE_REQUEST = 3 * BUZZ_SEARCH_PEOPLE_SHOWN
const val BUZZ_SEARCH_RECENT_MAX = 6
/** これより長い本文は書式を解釈せず、先頭だけを出す（公式の `_richSearchPreviewLimit`）。 */
const val BUZZ_SEARCH_RICH_PREVIEW_LIMIT = 512
/** 入力が止まってから検索するまで。 */
const val BUZZ_SEARCH_DEBOUNCE_MILLIS = 300L

/** チャンネル名の一致の良さ。小さいほど良い（公式の `MatchRank`）。 */
object BuzzMatchRank {
    const val EXACT = 0
    const val PREFIX = 1
    /** 途中の語の頭で一致。 */
    const val WORD_START = 2
    const val SUBSTRING = 3
    /** 文字が順に現れ、それぞれのまとまりが語の頭から始まる（`tlp` → `team-launch-planning`）。 */
    const val WORD_RUNS = 4
    /** 文字が順に現れる。 */
    const val IN_ORDER = 5
}

private fun isWordChar(codePoint: String?): Boolean = codePoint != null && codePoint.any { it.isLetterOrDigit() }

/** 文字列を書記素ではなくコードポイントの並びに分ける（サロゲートペアを1文字として扱う）。 */
private fun codePoints(value: String): List<String> {
    val out = ArrayList<String>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        val next = if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) 2 else 1
        out += value.substring(i, i + next)
        i += next
    }
    return out
}

/** [label] が [needle]（小文字にしたもの）にどれだけ一致するか。一致しなければ null（公式の `matchRank`）。 */
fun buzzMatchRank(label: String, needle: String): Int? {
    val text = label.lowercase()
    if (text == needle) return BuzzMatchRank.EXACT

    var substring = false
    var at = text.indexOf(needle)
    while (at >= 0) {
        if (at == 0) return BuzzMatchRank.PREFIX
        if (!isWordChar(text[at - 1].toString())) return BuzzMatchRank.WORD_START
        substring = true
        at = text.indexOf(needle, at + 1)
    }
    if (substring) return BuzzMatchRank.SUBSTRING

    // 入力の空白は語の区切りにすぎないので、ここからは比べない。
    val letters = codePoints(needle.replace(Regex("\\s+"), ""))
    if (letters.isEmpty()) return null
    val chars = codePoints(text)

    var found = 0
    for (char in chars) if (found < letters.size && char == letters[found]) found++
    if (found < letters.size) return null

    // letters[i..] が chars[j..] に、まとまりごとに語の頭から一致するか。inRun は直前の文字が chars[j - 1] に一致した。
    val memo = HashMap<Int, Boolean>()
    fun wordRuns(i: Int, j: Int, inRun: Boolean): Boolean {
        if (i == letters.size) return true
        if (j == chars.size) return false
        val key = (i * (chars.size + 1) + j) * 2 + (if (inRun) 1 else 0)
        memo[key]?.let { return it }
        val takes = chars[j] == letters[i] &&
            (inRun || !isWordChar(chars.getOrNull(j - 1))) &&
            wordRuns(i + 1, j + 1, true)
        val result = takes || wordRuns(i, j + 1, false)
        memo[key] = result
        return result
    }
    return if (wordRuns(0, 0, false)) BuzzMatchRank.WORD_RUNS else BuzzMatchRank.IN_ORDER
}

/** 検索の候補にするチャンネル。 */
data class BuzzSearchChannel(
    val ref: GroupRef,
    val metadata: GroupMetadata,
    val joined: Boolean,
)

private fun channelNeedle(query: String): String = query.trim().lowercase().removePrefix("#")

/**
 * チャンネルを検索の順に並べる（公式の `rankSearchChannels`）。名前の一致の良さ、アーカイブ済みは半段後ろ、
 * 同じなら参加中を先に（参加中どうしは [channels] の順）、未参加は名前順。DM は出さない。
 * アーカイブ済みと、`#` だけの入力では参加中だけ。
 */
fun rankBuzzSearchChannels(channels: List<BuzzSearchChannel>, query: String): List<BuzzSearchChannel> {
    val needle = channelNeedle(query)
    data class Ranked(val channel: BuzzSearchChannel, val rank: Double, val order: Int)
    val ranked = mutableListOf<Ranked>()
    for (channel in channels) {
        val meta = channel.metadata
        if (meta.layout == GroupLayout.Direct || meta.layout == GroupLayout.Hidden) continue
        val visible = if (meta.isArchived || needle.isEmpty()) channel.joined else true
        if (!visible) continue
        val rank = buzzMatchRank(meta.name, needle) ?: continue
        ranked += Ranked(channel, rank + if (meta.isArchived) 0.5 else 0.0, ranked.size)
    }
    return ranked.sortedWith { a, b ->
        val byRank = a.rank.compareTo(b.rank)
        when {
            byRank != 0 -> byRank
            a.channel.joined != b.channel.joined -> if (a.channel.joined) -1 else 1
            a.channel.joined -> a.order.compareTo(b.order)
            else -> a.channel.metadata.name.compareTo(b.channel.metadata.name).takeIf { it != 0 }
                ?: a.channel.ref.groupId.compareTo(b.channel.ref.groupId)
        }
    }.take(BUZZ_SEARCH_CHANNEL_LIMIT).map { it.channel }
}

// ---- 最近の検索 ----

/** [query] を最近の検索の先頭に入れる。大文字小文字を無視して重複を消し、新しい書き方を残す。空なら変えない。 */
fun recordRecentSearch(current: List<String>, query: String): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return current
    val normalized = trimmed.lowercase()
    return (listOf(trimmed) + current.filter { it.lowercase() != normalized }).take(BUZZ_SEARCH_RECENT_MAX)
}

fun encodeRecentSearches(searches: List<String>): String = searches.joinToString("\n") { it.replace('\n', ' ') }

fun decodeRecentSearches(stored: String?): List<String> =
    stored.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.take(BUZZ_SEARCH_RECENT_MAX)

fun buzzRecentSearchesKey(pubkey: String, relayUrl: String): String = "chat.buzzSearch.recent.$pubkey.$relayUrl"

// ---- フィルター ----

/** メッセージの検索（公式モバイル版の `_searchMessages`）。`#h` も `search_mode` も付けない。 */
fun buzzMessageSearchFilter(query: String): NostrFilter =
    NostrFilter(kinds = BUZZ_SEARCH_MESSAGE_KINDS, search = query.trim(), limit = BUZZ_SEARCH_MESSAGE_LIMIT)

/** 人の検索の代わり（NIP-50 で kind 0 を探す）。WebSocket では `search_mode` を使えないので全文検索になる。 */
fun buzzPeopleSearchFilter(query: String): NostrFilter =
    NostrFilter(kinds = listOf(0), search = query.trim(), limit = BUZZ_SEARCH_PEOPLE_REQUEST)

/** 前方一致で探すときの、最後の語の最短の長さ（リレーの `PREFIX_MIN_CHARS`、デスクトップの `MESSAGE_SEARCH_MIN_CHARS`）。 */
const val BUZZ_PREFIX_SEARCH_MIN_CHARS = 3

private fun hasWordChar(text: String): Boolean = text.any { it.isLetterOrDigit() }

/**
 * デスクトップ版の `searchTextSyntax` の要点: 閉じた引用符の語句か、`-語` の除外があれば全文検索の書き方。
 * （引用符は開くたび・閉じるたびに数え、`-` は語の頭のものだけを除外とみなす。）
 */
private fun usesFullTextSyntax(text: String): Boolean {
    val parts = text.split('"')
    parts.forEachIndexed { index, part ->
        if (index % 2 == 1) {
            if (index < parts.size - 1 && hasWordChar(part)) return true
        } else {
            part.split(Regex("\\s+")).forEach { word ->
                val minuses = word.takeWhile { it == '-' }.length
                if (minuses % 2 == 1 && hasWordChar(word)) return true
            }
        }
    }
    return false
}

/**
 * メッセージを前方一致（`/query` の `search_mode: "prefix"`、デスクトップ版と同じ）で探せるか。
 * 最後の語が [BUZZ_PREFIX_SEARCH_MIN_CHARS] 文字以上で、全文検索の書き方（語句・除外）でないとき。
 * 探せないときは全文検索（WebSocket の NIP-50）にする（docs/buzz-search-design.md 3.2）。
 */
fun buzzPrefixSearchable(query: String): Boolean {
    val text = query.trim()
    if (text.isEmpty() || usesFullTextSyntax(text)) return false
    val last = text.split(Regex("\\s+")).last()
    return codePoints(last).size >= BUZZ_PREFIX_SEARCH_MIN_CHARS
}

/** 前方一致のメッセージ検索の `POST /query` の本文（フィルターの配列）。 */
fun buzzMessagePrefixQueryBody(query: String): String = kotlinx.serialization.json.buildJsonArray {
    add(
        buildJsonObject {
            put("kinds", kotlinx.serialization.json.buildJsonArray { BUZZ_SEARCH_MESSAGE_KINDS.forEach { add(JsonPrimitive(it)) } })
            put("search", query.trim())
            put("search_mode", "prefix")
            put("limit", BUZZ_SEARCH_MESSAGE_LIMIT)
        },
    )
}.toString()

/** `POST /profiles/search` の本文。 */
fun buzzProfileSearchBody(query: String): String = buildJsonObject {
    put("query", query.trim())
    put("limit", BUZZ_SEARCH_PEOPLE_REQUEST)
}.toString()

/** `/profiles/search` の名前の上限（リレーの `QUERY_MAX_CHARS`）。超える入力は NIP-50 で探す。 */
const val BUZZ_PROFILE_SEARCH_QUERY_MAX = 100

private val searchJson = Json { ignoreUnknownKeys = true }

/** `/query`・`/profiles/search` の応答（イベントの配列）。署名を確かめ、通らないものは捨てる。配列でなければ null。 */
fun parseBuzzEventArray(body: String, verify: (NostrEvent) -> Boolean): List<NostrEvent>? {
    val array = runCatching { searchJson.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
    return array.mapNotNull { element ->
        runCatching { searchJson.decodeFromJsonElement(NostrEvent.serializer(), element) }.getOrNull()
    }.filter(verify)
}

// ---- メッセージの当たり ----

/** 当たりを押したときに開く画面。 */
sealed interface BuzzSearchTarget {
    data class Channel(val ref: GroupRef) : BuzzSearchTarget
    data class Thread(val ref: GroupRef, val rootId: String) : BuzzSearchTarget
}

data class BuzzMessageHit(
    val id: String,
    val ref: GroupRef,
    val kind: Int,
    /** 表示する作者（リレーが署名したものは `actor`）。 */
    val author: String,
    val createdAt: Long,
    val preview: String,
    val target: BuzzSearchTarget,
)

private fun agentBody(content: String): String = runCatching {
    ((searchJson.parseToJsonElement(content) as JsonObject)["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}.getOrNull() ?: content

/**
 * 検索の当たりを、リレーの並び（関連度順）のまま行にする。チャンネルがちょうど1つ（`h` が1つ）のものだけ。
 * 押したときは、フォーラム投稿はその投稿、コメント・スレッドの返信はルートのスレッド、それ以外はチャンネルを開く。
 */
fun buzzMessageHits(events: List<NostrEvent>, relayUrl: String, relayPubkey: String?): List<BuzzMessageHit> {
    val seen = HashSet<String>()
    return events.mapNotNull { event ->
        if (event.kind !in BUZZ_SEARCH_MESSAGE_KINDS || !seen.add(event.id)) return@mapNotNull null
        val channel = event.tags.filter { it.firstOrNull() == "h" }.singleOrNull()?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val ref = GroupRef(relayUrl, channel)
        val target = when {
            event.kind == BUZZ_FORUM_POST_KIND -> BuzzSearchTarget.Thread(ref, event.id)
            else -> buzzThreadRootId(event)?.let { BuzzSearchTarget.Thread(ref, it) } ?: BuzzSearchTarget.Channel(ref)
        }
        val body = if (event.kind == BUZZ_AGENT_MESSAGE_KIND) agentBody(event.content) else event.content
        BuzzMessageHit(
            id = event.id,
            ref = ref,
            kind = event.kind,
            author = groupMessageAuthor(event, GroupFlavor.Buzz, relayPubkey),
            createdAt = event.createdAt,
            preview = buzzSearchPreview(body),
            target = target,
        )
    }
}

/**
 * 当たりの本文を、2行に収める平文にする。Markdown の記号は外し、段落は空白でつなぐ。
 * [BUZZ_SEARCH_RICH_PREVIEW_LIMIT] を超える本文は書式を解釈せず、先頭だけを出す（公式と同じ）。
 */
fun buzzSearchPreview(body: String): String {
    if (body.length > BUZZ_SEARCH_RICH_PREVIEW_LIMIT) {
        var end = BUZZ_SEARCH_RICH_PREVIEW_LIMIT
        if (body[end - 1].isHighSurrogate()) end--
        return body.substring(0, end).replace(Regex("\\s+"), " ") + "…"
    }
    val blocks = parseMarkdownLite(body)
    val media = blocks.count { it is MdBlock.Image }
    val text = blocks.mapNotNull { block ->
        when (block) {
            is MdBlock.Paragraph -> block.inlines.plainText()
            is MdBlock.Heading -> block.inlines.plainText()
            is MdBlock.Quote -> block.inlines.plainText()
            is MdBlock.CodeBlock -> block.text
            is MdBlock.Image -> null
        }
    }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    return when {
        text.isNotEmpty() && media > 0 -> "📷 $text"
        text.isNotEmpty() -> text
        media > 0 -> "📷 画像"
        else -> ""
    }
}

// ---- 人 ----

data class BuzzSearchPerson(val pubkey: String, val profile: NostrProfile)

/** 人の検索の答え（kind 0）。同じ人は新しいものだけ、自分を除き、届いた順で [BUZZ_SEARCH_PEOPLE_SHOWN] 人まで。 */
fun buzzSearchPeople(events: List<NostrEvent>, me: String?): List<BuzzSearchPerson> {
    val latest = LinkedHashMap<String, NostrEvent>()
    events.filter { it.kind == 0 && it.pubkey != me }.forEach { event ->
        val current = latest[event.pubkey]
        if (current == null || event.createdAt > current.createdAt) latest[event.pubkey] = event
    }
    return latest.values.mapNotNull { event -> event.toProfile()?.let { BuzzSearchPerson(event.pubkey, it) } }
        .take(BUZZ_SEARCH_PEOPLE_SHOWN)
}
