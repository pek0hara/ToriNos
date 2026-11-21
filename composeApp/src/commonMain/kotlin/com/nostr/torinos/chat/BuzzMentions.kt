package com.nostr.torinos.chat

/*
 * Buzz のメンション。Buzz（buzz-app の mention-draft.ts、relay/messages.ts、profile-mentions.ts）と同じ形にする。
 * - 本文には `@表示名` をそのまま書き、宛先は `["p", pubkey]` で付ける（最大32人、チャンネルのメンバーだけ）。
 * - Buzz は p タグの人の表示名と本文の `@表示名` を照らし合わせてリンクにする。表示名は kind 0 の
 *   `display_name`、無ければ `name`、どちらも無ければ pubkey の先頭10文字。
 * - 文字・数字・`_`・`@`・`/`・`\` の直後の `@` はメンションにしない（メールアドレスなど）。日本語の文字も含む。
 */

/** Buzz が1件のメッセージに付けられる宛先の上限。 */
const val BUZZ_MAX_MENTIONS = 32

/** 入力中のメンション。[start] は `@` の位置、[query] は `@` からカーソルまでの文字。 */
data class MentionQuery(val start: Int, val query: String)

/** メンションの候補（チャンネルのメンバー）。[name] は本文に書く表示名。 */
data class MentionCandidate(val pubkey: String, val name: String)

/** 候補を選んだ後の本文とカーソル。 */
data class MentionInsertion(val text: String, val cursor: Int)

private const val MAX_QUERY_LENGTH = 32

private fun Char.blocksMentionBefore(): Boolean = isLetterOrDigit() || this == '_' || this == '@' || this == '/' || this == '\\'

private fun Char.continuesName(): Boolean = isLetterOrDigit() || this == '_'

/** カーソルの直前が `@…` なら、その位置と入力中の文字。空白・改行で終わる。 */
fun activeMentionQuery(text: String, cursor: Int): MentionQuery? {
    if (cursor <= 0 || cursor > text.length) return null
    var i = cursor - 1
    while (i >= 0 && cursor - i <= MAX_QUERY_LENGTH + 1) {
        val c = text[i]
        if (c == '@') {
            if (i > 0 && text[i - 1].blocksMentionBefore()) return null
            return MentionQuery(start = i, query = text.substring(i + 1, cursor))
        }
        if (c.isWhitespace()) return null
        i--
    }
    return null
}

/** Buzz が本文の照合に使う表示名。改行を含む名前はメンションにできない。 */
fun buzzMentionName(pubkey: String, profileName: String?): String =
    profileName?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it } ?: pubkey.take(10)

/**
 * [query] に合うメンバー。名前の先頭が合うものを先に、途中が合うものを後に、それぞれメンバー一覧の順で並べる。
 * 自分は出さない。
 */
fun mentionCandidates(
    members: List<String>,
    profileName: (String) -> String?,
    query: String,
    ownPubkey: String?,
    limit: Int = 20,
): List<MentionCandidate> {
    val needle = query.lowercase()
    val all = members.distinct().filter { it != ownPubkey }.map { MentionCandidate(it, buzzMentionName(it, profileName(it))) }
    if (needle.isEmpty()) return all.take(limit)
    val prefix = all.filter { it.name.lowercase().startsWith(needle) }
    val contains = all.filter { it !in prefix && needle in it.name.lowercase() }
    return (prefix + contains).take(limit)
}

/** 入力中の `@…` を `@表示名 ` に置き換える。後ろが空白ならそれを使い、空白を重ねない。 */
fun insertMention(text: String, query: MentionQuery, cursor: Int, name: String): MentionInsertion {
    val before = text.substring(0, query.start)
    val after = text.substring(cursor)
    val mention = "@$name"
    return if (after.firstOrNull()?.isWhitespace() == true) {
        MentionInsertion(before + mention + after, cursor = before.length + mention.length + 1)
    } else {
        MentionInsertion("$before$mention $after", cursor = before.length + mention.length + 1)
    }
}

/**
 * 送るときの宛先。選んだ人のうち、本文に `@表示名` が残っている人だけ（消した・書き換えたメンションは送らない）。
 * 重ねず、[BUZZ_MAX_MENTIONS] 人まで。
 */
fun mentionedPubkeys(text: String, picked: List<MentionCandidate>): List<String> =
    picked.distinctBy { it.pubkey }
        .filter { containsMention(text, it.name) }
        .map { it.pubkey }
        .take(BUZZ_MAX_MENTIONS)

/**
 * 入力欄で色を付ける、選んだ人の `@表示名` の位置（送るときに宛先になるものと同じ規則）。
 * 長い名前を先に取り、重ならないようにする。
 */
fun pickedMentionRanges(text: String, picked: List<MentionCandidate>): List<IntRange> {
    if (picked.isEmpty() || '@' !in text) return emptyList()
    val names = picked.map { it.name }.distinct().sortedByDescending { it.length }
    val ranges = ArrayList<IntRange>()
    var i = 0
    while (i < text.length) {
        if (text[i] != '@' || (i > 0 && text[i - 1].blocksMentionBefore())) {
            i++
            continue
        }
        val name = names.firstOrNull { name ->
            val end = i + 1 + name.length
            text.startsWith(name, i + 1) && (end == text.length || !text[end].continuesName())
        }
        if (name == null) {
            i++
            continue
        }
        val end = i + 1 + name.length
        ranges += i until end
        i = end
    }
    return ranges
}

private fun containsMention(text: String, name: String): Boolean {
    val mention = "@$name"
    var from = 0
    while (true) {
        val at = text.indexOf(mention, from)
        if (at < 0) return false
        val end = at + mention.length
        val startsOk = at == 0 || !text[at - 1].blocksMentionBefore()
        val endsOk = end == text.length || !text[end].continuesName()
        if (startsOk && endsOk) return true
        from = at + 1
    }
}

/** 宛先の `p` タグを足す。既にある人は足さない。 */
fun GroupEventDraft.withMentionTags(pubkeys: List<String>): GroupEventDraft {
    val existing = tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) }.toMutableSet()
    val added = pubkeys.filter { existing.add(it) }.map { listOf("p", it) }
    return if (added.isEmpty()) this else copy(tags = tags + added)
}

/** 本文の一部。[pubkey] があればその人へのメンション。 */
data class MentionSpan(val text: String, val pubkey: String? = null)

private val HEX64_PATTERN = Regex("^[0-9a-f]{64}$")

/** `@名前 (pubkey)` の形の、名前の後ろの部分。同じ名前の人がいても、この pubkey の人に決まる。 */
private val MENTION_QUALIFIER = Regex("""^ \(([a-fA-F0-9]{64})\)""")

/** メッセージがメンションしている人（`p` タグと `mention` タグ）。Buzz の `fold.ts` と同じ。 */
fun buzzMentionTargets(event: com.nostr.torinos.model.NostrEvent): List<String> =
    event.tags.mapNotNull { tag ->
        val name = tag.firstOrNull()
        val value = tag.getOrNull(1)
        when {
            value == null || !HEX64_PATTERN.matches(value) -> null
            name == "p" -> value
            name == "mention" && tag.size == 2 -> value
            else -> null
        }
    }.distinct()

/** プロフィールを読んでおく人: メンションの相手（[buzzMentionTargets]）と、本文に `nostr:npub1…` で書かれた人。 */
fun buzzProfileTargets(event: com.nostr.torinos.model.NostrEvent): List<String> =
    (buzzMentionTargets(event) + com.nostr.torinos.model.extractNpubReferences(event.content).map { it.pubkey }).distinct()

/** 表示名 → その名前の人。同じ名前の人が複数いれば、その名前はリンクにしない（[splitMentions]）。 */
fun mentionNameTable(targets: List<String>, profileName: (String) -> String?): Map<String, Set<String>> {
    val table = LinkedHashMap<String, MutableSet<String>>()
    targets.forEach { pubkey -> table.getOrPut(buzzMentionName(pubkey, profileName(pubkey))) { linkedSetOf() } += pubkey }
    return table
}

/**
 * 本文の `@表示名` を、宛先の人へのメンションに分ける（Buzz の `profileMentionParts` と同じ規則）。
 * - 長い名前から照らし合わせ、名前の直後に文字・数字・`_` が続けば別の名前とみなす。
 * - 同じ名前の人が複数いれば文字のまま。`@名前 (pubkey)` なら大文字小文字を問わずその人に決める。
 */
fun splitMentions(text: String, names: Map<String, Set<String>>): List<MentionSpan> {
    if (names.isEmpty() || '@' !in text) return listOf(MentionSpan(text))
    val candidates = names.entries.sortedByDescending { it.key.length }
    val parts = ArrayList<MentionSpan>()
    var plain = 0
    var i = 0
    fun emit(end: Int, pubkey: String) {
        if (i > plain) parts += MentionSpan(text.substring(plain, i))
        parts += MentionSpan(text.substring(i, end), pubkey)
        plain = end
    }
    while (i < text.length) {
        if (text[i] != '@' || (i > 0 && text[i - 1].blocksMentionBefore())) {
            i++
            continue
        }
        val qualified = candidates.firstNotNullOfOrNull { (name, keys) ->
            if (!text.regionMatches(i + 1, name, 0, name.length, ignoreCase = true)) return@firstNotNullOfOrNull null
            val nameEnd = i + 1 + name.length
            val qualifier = MENTION_QUALIFIER.find(text.substring(nameEnd)) ?: return@firstNotNullOfOrNull null
            val key = qualifier.groupValues[1].lowercase()
            if (key in keys) (nameEnd + qualifier.value.length) to key else null
        }
        if (qualified != null) {
            emit(qualified.first, qualified.second)
            i = qualified.first
            continue
        }
        val match = candidates.firstOrNull { (name, _) ->
            val end = i + 1 + name.length
            text.startsWith(name, i + 1) && (end == text.length || !text[end].continuesName())
        }
        if (match == null) {
            i++
            continue
        }
        val end = i + 1 + match.key.length
        // 誰のものでもない pubkey を付けた名前は文字のまま。短い名前に前の部分を取らせない。
        val qualifier = MENTION_QUALIFIER.find(text.substring(end))
        if (qualifier != null) {
            i = end + qualifier.value.length
            continue
        }
        if (match.value.size == 1) emit(end, match.value.first())
        i = end
    }
    if (plain < text.length) parts += MentionSpan(text.substring(plain))
    return parts.ifEmpty { listOf(MentionSpan(text)) }
}
