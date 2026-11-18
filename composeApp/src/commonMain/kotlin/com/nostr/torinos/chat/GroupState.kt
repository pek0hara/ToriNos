package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent

/** kind 39000 のグループ情報。 */
@kotlinx.serialization.Serializable
data class GroupMetadata(
    val groupId: String,
    val name: String,
    val picture: String,
    val about: String,
    /** メンバーだけが読める。 */
    val isPrivate: Boolean,
    /** メンバーだけが書ける。 */
    val isRestricted: Boolean,
    /** メンバー以外にはグループ情報も見せない。 */
    val isHidden: Boolean,
    /** 招待コード無しの参加要求を受け付けない。 */
    val isClosed: Boolean,
    /** 書けるkind。null は制限なし。 */
    val supportedKinds: Set<Int>?,
    val createdAt: Long,
    /** Buzz のチャンネル種別（最初の `t` タグ: stream / forum / dm / workflow / system）。 */
    val channelType: String? = null,
    /** Buzz のラベル（2つ目以降の `t` タグ）。 */
    val labels: List<String> = emptyList(),
    /** Buzz の DM の参加者（kind 39000 の `p` タグ。自分を含む）。 */
    val participants: List<String> = emptyList(),
    /** Buzz: チャンネルを作った人（`P` タグ）。 */
    val creator: String? = null,
    /** Buzz: アーカイブ済み（`["archived", "true"]`。稼働中のチャンネルにはタグが無い）。 */
    val isArchived: Boolean = false,
    /** Buzz: 参加した人の役割（`["join_role", "member" | "guest"]`）。標準の NIP-29 では null。 */
    val joinRole: String? = null,
    /** Buzz: 一覧に出さない（`["unlisted"]`。リンクを知っている人だけが見つけられる）。 */
    val isUnlisted: Boolean = false,
) {
    /** Buzz の Solo チャット（自分とエージェントの会話）。 */
    val isSolo: Boolean get() = BUZZ_SOLO_LABEL in labels

    val displayName: String get() = name.ifBlank { groupId }

    fun supportsKind(kind: Int): Boolean = supportedKinds?.contains(kind) ?: true

    /** 一覧と画面の種類。Buzz は `t`、標準は kind 11 だけを書けるグループをスレッド型とみなす。 */
    val layout: GroupLayout
        get() = when (channelType) {
            "forum" -> GroupLayout.Forum
            "stream" -> GroupLayout.Chat
            "dm" -> GroupLayout.Direct
            "workflow", "system" -> GroupLayout.Hidden
            else -> if (supportedKinds != null && GROUP_THREAD_KIND in supportedKinds && GROUP_CHAT_KIND !in supportedKinds) {
                GroupLayout.Forum
            } else {
                GroupLayout.Chat
            }
        }
}

/** グループの画面の種類。 */
enum class GroupLayout { Chat, Forum, Hidden, Direct }

/** Buzz の Solo チャットのラベル。 */
const val BUZZ_SOLO_LABEL = "buzz.solo"
/** Buzz: 自分が隠した DM の一覧（`h` タグ）。リレーが署名する。 */
const val BUZZ_DM_VISIBILITY_KIND = 30622

/** NIP-29 のメンバーかどうか。 */
enum class GroupMembership { Member, NotMember }

private val HEX64 = Regex("^[0-9a-f]{64}$")

private fun NostrEvent.firstTagValue(name: String): String? =
    tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

private fun NostrEvent.hasFlag(name: String): Boolean = tags.any { it.firstOrNull() == name }

/** リレーの鍵（NIP-11 の `self`）が分かっていれば、その鍵で署名されたものだけを受け付ける。 */
private fun NostrEvent.isSignedByRelay(relayPubkey: String?): Boolean =
    relayPubkey == null || pubkey == relayPubkey

fun parseGroupMetadata(event: NostrEvent, relayPubkey: String?): GroupMetadata? {
    if (event.kind != GROUP_METADATA_KIND || !event.isSignedByRelay(relayPubkey)) return null
    val groupId = event.firstTagValue("d")?.takeIf { it.isNotEmpty() } ?: return null
    val supported = event.tags.firstOrNull { it.firstOrNull() == "supported_kinds" }
        ?.drop(1)
        ?.mapNotNull(String::toIntOrNull)
        ?.toSet()
    return GroupMetadata(
        groupId = groupId,
        name = event.firstTagValue("name").orEmpty(),
        picture = event.firstTagValue("picture").orEmpty(),
        about = event.firstTagValue("about").orEmpty(),
        isPrivate = event.hasFlag("private"),
        isRestricted = event.hasFlag("restricted"),
        isHidden = event.hasFlag("hidden"),
        isClosed = event.hasFlag("closed"),
        supportedKinds = supported,
        createdAt = event.createdAt,
        channelType = event.firstTagValue("t"),
        labels = event.tags.filter { it.firstOrNull() == "t" }.drop(1).mapNotNull { it.getOrNull(1) },
        participants = event.tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1)?.takeIf(HEX64::matches) }.distinct(),
        creator = event.firstTagValue("P")?.takeIf(HEX64::matches),
        isArchived = event.firstTagValue("archived") == "true",
        joinRole = event.firstTagValue("join_role")?.takeIf { it.isNotBlank() },
        isUnlisted = event.hasFlag("unlisted"),
    )
}

/**
 * DM の見出し。自分以外の参加者の名前を並べる。[nameOf] が null を返す相手は公開鍵の先頭を出す。
 */
fun dmDisplayName(participants: List<String>, me: String, nameOf: (String) -> String?): String {
    if (participants.isEmpty()) return "DM"
    val others = participants.filter { it != me }
    if (others.isEmpty()) return "自分"
    return others.joinToString("、") { pubkey -> nameOf(pubkey)?.takeIf { it.isNotBlank() } ?: (pubkey.take(8) + "…") }
}

/** 自分が隠した DM の ID。最新の [BUZZ_DM_VISIBILITY_KIND]（リレー署名）の `h` タグ。 */
fun hiddenDmIds(events: Iterable<NostrEvent>, relayPubkey: String?): Set<String> {
    if (relayPubkey == null) return emptySet()
    val latest = events
        .filter { it.kind == BUZZ_DM_VISIBILITY_KIND && it.pubkey == relayPubkey }
        .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
        ?: return emptySet()
    return latest.tags.filter { it.firstOrNull() == "h" }.mapNotNull { it.getOrNull(1) }.toSet()
}

/** グループIDごとに最新の kind 39000。 */
fun latestGroupMetadata(events: Iterable<NostrEvent>, relayPubkey: String?): Map<String, GroupMetadata> {
    val latest = mutableMapOf<String, GroupMetadata>()
    events.forEach { event ->
        val meta = parseGroupMetadata(event, relayPubkey) ?: return@forEach
        val current = latest[meta.groupId]
        if (current == null || meta.createdAt > current.createdAt) latest[meta.groupId] = meta
    }
    return latest
}

/** kind 39001 の管理者。 */
fun parseGroupAdmins(event: NostrEvent, relayPubkey: String?): Set<String> {
    if (event.kind != GROUP_ADMINS_KIND || !event.isSignedByRelay(relayPubkey)) return emptySet()
    return event.tags
        .filter { it.firstOrNull() == "p" }
        .mapNotNull { it.getOrNull(1)?.takeIf(HEX64::matches) }
        .toSet()
}

/**
 * 自分を対象にした kind 9000（追加）と 9001（削除）のうち、新しいほうで判定する。どちらも無ければ非メンバー。
 * [authorities] はリレーの鍵と管理者。それ以外の署名は無視する。
 */
fun membershipOf(
    pubkey: String,
    groupId: String,
    moderationEvents: Iterable<NostrEvent>,
    authorities: Set<String>,
): GroupMembership {
    val latest = moderationEvents
        .filter { it.kind == GROUP_PUT_USER_KIND || it.kind == GROUP_REMOVE_USER_KIND }
        .filter { it.pubkey in authorities }
        .filter { it.firstTagValue("h") == groupId }
        .filter { event -> event.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == pubkey } }
        .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
    return if (latest?.kind == GROUP_PUT_USER_KIND) GroupMembership.Member else GroupMembership.NotMember
}

/** Buzz のメンバー判定: 最新の kind 39002（リレー署名）に自分が入っているか。 */
fun buzzMembershipOf(pubkey: String, groupId: String, memberLists: Iterable<NostrEvent>, relayPubkey: String?): GroupMembership {
    val latest = memberLists
        .filter { it.kind == GROUP_MEMBERS_KIND && (relayPubkey == null || it.pubkey == relayPubkey) }
        .filter { it.firstTagValue("d") == groupId }
        .maxByOrNull { it.createdAt } ?: return GroupMembership.NotMember
    return if (latest.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == pubkey }) GroupMembership.Member else GroupMembership.NotMember
}
