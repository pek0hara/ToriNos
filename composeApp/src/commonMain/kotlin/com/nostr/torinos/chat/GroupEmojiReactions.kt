package com.nostr.torinos.chat

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EMOJI_TAG
import com.nostr.torinos.emoji.sameEmojiAs
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.ReactionOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * グループの絵文字リアクション（docs/buzz-emoji-reaction-design.md）。
 *
 * - Buzz のコミュニティの絵文字は、メンバーそれぞれの kind 30030（`d` = `buzz:custom-emoji`）の和集合。
 * - 「最近使った」はアカウント全体の一覧（フィードと共有）と、コミュニティごとの一覧の2つ。
 *   コミュニティの絵文字（メンバー限定の画像）はコミュニティの一覧にだけ入れる。
 */

/** Buzz のコミュニティの絵文字セットの `d`。 */
const val BUZZ_EMOJI_SET_D_TAG = "buzz:custom-emoji"

/** 1回に読むセットの数（buzz-app と同じ）。ちょうどこの数が返ったら欠けている可能性がある。 */
const val BUZZ_EMOJI_SET_LIMIT = 500

/** チャットの「最近使った」の並びに出す数（フィードの `QuickReactionMenu` と同じ）。 */
const val GROUP_QUICK_REACTION_LIMIT = 16

/** コミュニティごとの「最近使った」に残す数（アカウント全体の一覧と同じ）。 */
const val GROUP_RECENT_REACTION_LIMIT = 24

/** Buzz の shortcode の規則（`normalize_custom_emoji_shortcode`）。 */
private val BUZZ_SHORTCODE = Regex("^[a-z0-9_-]{1,64}$")

/** Buzz のリアクションの本文の上限（コードポイント）。 */
private const val BUZZ_REACTION_MAX_CODE_POINTS = 64

data class BuzzEmojiPalette(
    /** shortcode の順。 */
    val emojis: List<CustomEmoji>,
    /** 読んだセットが上限に達した（一覧が欠けているかもしれない）。 */
    val truncated: Boolean,
) {
    /** shortcode（小文字）→ 画像の URL。 */
    val byShortcode: Map<String, String> by lazy { emojis.associate { it.shortcode to it.imageUrl } }
}

fun buzzEmojiPaletteFilter(): NostrFilter =
    NostrFilter(kinds = listOf(30030), dTags = listOf(BUZZ_EMOJI_SET_D_TAG), limit = BUZZ_EMOJI_SET_LIMIT)

/** 前後の空白とコロンを除いて小文字にし、Buzz の規則を満たさなければ null。 */
fun normalizeBuzzShortcode(raw: String): String? =
    raw.trim().trim(':').trim().lowercase().takeIf(BUZZ_SHORTCODE::matches)

/**
 * メンバー全員のセットの和集合（buzz-cli の `union_custom_emoji`、buzz-app の `emoji-directory.ts` と同じ規則）。
 * shortcode ごとに新しいセットのものを採り、同時刻なら URL の小さい方。同じセット内の重複は先勝ち。
 */
fun unionBuzzEmojiSets(events: List<NostrEvent>, limit: Int = BUZZ_EMOJI_SET_LIMIT): BuzzEmojiPalette {
    val sets = events.filter { event ->
        event.kind == 30030 && event.tags.any { it.getOrNull(0) == "d" && it.getOrNull(1) == BUZZ_EMOJI_SET_D_TAG }
    }
    val chosen = mutableMapOf<String, Pair<String, Long>>()
    sets.forEach { event ->
        val seen = mutableSetOf<String>()
        event.tags.forEach { tag ->
            if (tag.getOrNull(0) != EMOJI_TAG) return@forEach
            val shortcode = tag.getOrNull(1)?.let(::normalizeBuzzShortcode) ?: return@forEach
            val url = tag.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            if (!seen.add(shortcode)) return@forEach
            val current = chosen[shortcode]
            val keepCurrent = current != null &&
                (current.second > event.createdAt || (current.second == event.createdAt && current.first <= url))
            if (!keepCurrent) chosen[shortcode] = url to event.createdAt
        }
    }
    return BuzzEmojiPalette(
        emojis = chosen.entries.sortedBy { it.key }.map { CustomEmoji(it.key, it.value.first) },
        truncated = events.size >= limit,
    )
}

/**
 * グループに送れるリアクションか。
 * Buzz は本文64コードポイント以内、カスタム絵文字は正規化できる shortcode に限る（リレーが拒否するため）。
 * 標準の NIP-29 はフィードと同じく制限しない。
 */
fun isGroupReactionSendable(option: ReactionOption, flavor: GroupFlavor): Boolean = when (option) {
    is ReactionOption.Unicode -> option.value.isNotBlank() &&
        (flavor != GroupFlavor.Buzz || option.value.codePointCount() <= BUZZ_REACTION_MAX_CODE_POINTS)
    is ReactionOption.Custom -> option.imageUrl.isNotBlank() &&
        (if (flavor == GroupFlavor.Buzz) normalizeBuzzShortcode(option.shortcode) != null else option.shortcode.isNotBlank())
}

private fun String.codePointCount(): Int = count { !it.isLowSurrogate() }

/** チャットの「最近使った」の並び。コミュニティの一覧 → アカウント全体の一覧の順で、送れるものだけ。 */
fun groupQuickReactionOptions(
    communityRecent: List<ReactionOption>,
    accountRecent: List<ReactionOption>,
    isSendable: (ReactionOption) -> Boolean,
    limit: Int = GROUP_QUICK_REACTION_LIMIT,
): List<ReactionOption> =
    (communityRecent + accountRecent)
        .filter(isSendable)
        .distinctBy { it.key }
        .take(limit)

/**
 * アカウント全体の「最近使った」にも記録するか。Unicode と自分の絵文字（お気に入り・登録セット）だけ。
 * それ以外のカスタム絵文字（コミュニティの絵文字、他の人のリアクションから押したもの）は、
 * メンバー限定の画像かもしれないので、フィードの一覧に入れない。
 */
fun recordsInAccountRecent(option: ReactionOption, ownEmojis: List<CustomEmoji>): Boolean = when (option) {
    is ReactionOption.Unicode -> true
    is ReactionOption.Custom -> CustomEmoji(option.shortcode, option.imageUrl).let { used -> ownEmojis.any { it.sameEmojiAs(used) } }
}

/** コミュニティごとの「最近使った」に [option] を先頭で入れる。 */
fun recordGroupRecent(current: List<ReactionOption>, option: ReactionOption): List<ReactionOption> =
    (listOf(option.withoutSetAddress()) + current.filterNot { it.key == option.key }).take(GROUP_RECENT_REACTION_LIMIT)

private fun ReactionOption.withoutSetAddress(): ReactionOption =
    if (this is ReactionOption.Custom) copy(setAddress = null) else this

fun groupRecentStorageKey(pubkey: String, relayUrl: String): String = "group_recent_reactions:$pubkey:$relayUrl"

@Serializable
private data class StoredGroupReaction(
    val unicode: String? = null,
    val shortcode: String? = null,
    val url: String? = null,
)

private val recentJson = Json { ignoreUnknownKeys = true }

/** 保存する形。セットのアドレスは保存しない（送るときに自分のセットから引き直す）。 */
fun encodeGroupRecent(options: List<ReactionOption>): String =
    recentJson.encodeToString(
        ListSerializer(StoredGroupReaction.serializer()),
        options.map { option ->
            when (option) {
                is ReactionOption.Unicode -> StoredGroupReaction(unicode = option.value)
                is ReactionOption.Custom -> StoredGroupReaction(shortcode = option.shortcode, url = option.imageUrl)
            }
        },
    )

fun decodeGroupRecent(stored: String?): List<ReactionOption> {
    if (stored.isNullOrBlank()) return emptyList()
    val items = runCatching { recentJson.decodeFromString(ListSerializer(StoredGroupReaction.serializer()), stored) }
        .getOrNull() ?: return emptyList()
    return items.mapNotNull { item ->
        when {
            !item.unicode.isNullOrBlank() -> ReactionOption.Unicode(item.unicode)
            !item.shortcode.isNullOrBlank() && !item.url.isNullOrBlank() -> ReactionOption.Custom(item.shortcode, item.url)
            else -> null
        }
    }.distinctBy { it.key }.take(GROUP_RECENT_REACTION_LIMIT)
}

/**
 * リアクションを集計・取り消しする単位（buzz-app と同じく本文と画像の組）。
 * `:shortcode:` は大文字小文字を区別しない（Buzz が正規化するため）。
 */
fun groupReactionKey(content: String, emojiUrl: String?): String {
    val normalized = if (isShortcodeReaction(content)) content.lowercase() else content
    return "$normalized\n${emojiUrl.orEmpty()}"
}

private fun isShortcodeReaction(content: String): Boolean =
    content.length >= 3 && content.startsWith(":") && content.endsWith(":") && ':' !in content.substring(1, content.length - 1)

private fun shortcodeOf(content: String): String? =
    if (isShortcodeReaction(content)) content.substring(1, content.length - 1) else null

/** 送るときの本文と画像（`:shortcode:` は小文字にそろえて比べる）。 */
private fun ReactionOption.groupReactionKey(): String = when (this) {
    is ReactionOption.Unicode -> groupReactionKey(value, null)
    is ReactionOption.Custom -> groupReactionKey(":${shortcode}:", imageUrl.trim())
}

/** 集計の画像。タグが無い `:shortcode:` はコミュニティの一覧（[palette]: 小文字の shortcode → URL）から引く。 */
fun reactionImageUrl(summary: GroupReactionSummary, palette: Map<String, String>): String? =
    summary.emojiUrl ?: shortcodeOf(summary.content)?.let { palette[it.lowercase()] }

/** タグが無い `:shortcode:` のリアクションがあるか（あればコミュニティの一覧を読む）。 */
fun hasTagLessShortcodeReaction(summaries: List<GroupReactionSummary>): Boolean =
    summaries.any { it.emojiUrl == null && isShortcodeReaction(it.content) }

/** チップを押したときに送るもの。 */
fun GroupReactionSummary.toReactionOption(palette: Map<String, String>): ReactionOption {
    val shortcode = shortcodeOf(content)
    val url = reactionImageUrl(this, palette)
    return if (shortcode != null && url != null) ReactionOption.Custom(shortcode.lowercase(), url) else ReactionOption.Unicode(content)
}

/** [option] と同じ自分のリアクションがあれば、そのイベント（取り消すときに消す）。 */
fun ownReactionIdFor(summaries: List<GroupReactionSummary>, option: ReactionOption): String? {
    val key = option.groupReactionKey()
    return summaries.firstOrNull { it.reactedByMe && groupReactionKey(it.content, it.emojiUrl) == key }?.ownReactionId
}
