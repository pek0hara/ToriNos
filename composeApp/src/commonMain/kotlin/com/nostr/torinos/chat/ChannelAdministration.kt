package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent

// Buzz のチャンネルの owner / admin の操作（docs/buzz-admin-design.md 4.1）。
// ここでの判定は表示のためだけで、実際の可否はリレーが決める（拒否されたら理由を見せる）。

/** チャンネルでの役割（kind 39002 の `["p", pubkey, "", role]`）。 */
enum class ChannelRole(val wire: String?) {
    Owner("owner"), Admin("admin"), Member("member"), Guest("guest"), Bot("bot"),
    /** リレーが付けた知らない役割。操作の対象にしない。 */
    Unknown(null),
}

private val HEX64 = Regex("^[0-9a-f]{64}$")

private fun roleOf(value: String?): ChannelRole = when (value) {
    null, "" -> ChannelRole.Member
    else -> ChannelRole.entries.firstOrNull { it.wire == value } ?: ChannelRole.Unknown
}

private fun NostrEvent.dTag(): String? = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)

private fun latestMemberList(events: Iterable<NostrEvent>, groupId: String, relayPubkey: String?): NostrEvent? {
    if (relayPubkey == null) return null
    return events
        .filter { it.kind == GROUP_MEMBERS_KIND && it.pubkey == relayPubkey && it.dTag() == groupId }
        .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
}

/**
 * 最新の kind 39002（リレーの鍵の署名）から、メンバーごとの役割。リレーの鍵が分からないか、39002 が無ければ null
 * （署名者を確かめられない一覧で管理の操作を出さない）。
 */
fun channelRoles(events: Iterable<NostrEvent>, groupId: String, relayPubkey: String?): Map<String, ChannelRole>? {
    val latest = latestMemberList(events, groupId, relayPubkey) ?: return null
    val roles = LinkedHashMap<String, ChannelRole>()
    latest.tags.filter { it.firstOrNull() == "p" }.forEach { tag ->
        val key = tag.getOrNull(1)?.takeIf(HEX64::matches) ?: return@forEach
        if (key !in roles) roles[key] = roleOf(tag.getOrNull(3))
    }
    return roles
}

/** 最新の kind 39002 の ID。送った変更の反映待ちを、これと違う 39002 が届くまで続ける。 */
fun latestMemberListId(events: Iterable<NostrEvent>, groupId: String, relayPubkey: String?): String? =
    latestMemberList(events, groupId, relayPubkey)?.id

/** 自分がそのチャンネルでできること。 */
data class ChannelAuthority(
    val viewerRole: ChannelRole? = null,
    /** 役割の変更と追加。 */
    val canManage: Boolean = false,
    /** 外す、他人の発言の削除。 */
    val canRemove: Boolean = false,
    val canArchive: Boolean = false,
    val canUnarchive: Boolean = false,
    val canDelete: Boolean = false,
    /** 自分が唯一の owner（退出できない）。 */
    val isLastOwner: Boolean = false,
    /** 人を加えられる（投稿できるメンバーなら誰でも。Buzz の `decide_member_put`）。 */
    val canAdd: Boolean = false,
    /** 加えるときに選べる役割。管理者を付けられるのは owner / admin だけ。 */
    val addableRoles: List<ChannelRole> = emptyList(),
)

/** Buzz アプリの `memberAuthority` と `lifecycleRules` に合わせる。 */
fun channelAuthority(metadata: GroupMetadata?, roles: Map<String, ChannelRole>?, viewer: String?): ChannelAuthority {
    if (metadata == null || roles == null || viewer == null) return ChannelAuthority()
    val role = roles[viewer] ?: return ChannelAuthority()
    val direct = metadata.layout == GroupLayout.Direct
    val elevated = role == ChannelRole.Owner || role == ChannelRole.Admin
    val active = !direct && !metadata.isArchived
    // ゲスト以外は投稿でき、投稿できる人は人を加えられる。Solo は自分とエージェントの場所なので出さない。
    val canAdd = active && !metadata.isSolo && role != ChannelRole.Guest && role != ChannelRole.Unknown
    return ChannelAuthority(
        viewerRole = role,
        canManage = active && elevated && !metadata.isSolo,
        canRemove = active && elevated,
        canArchive = active && elevated,
        canUnarchive = !direct && metadata.isArchived && elevated,
        canDelete = active && role == ChannelRole.Owner,
        isLastOwner = !direct && role == ChannelRole.Owner && roles.values.count { it == ChannelRole.Owner } == 1,
        canAdd = canAdd,
        addableRoles = when {
            !canAdd -> emptyList()
            elevated -> listOf(ChannelRole.Member, ChannelRole.Guest, ChannelRole.Admin)
            else -> listOf(ChannelRole.Member, ChannelRole.Guest)
        },
    )
}

private fun isManageableTarget(roles: Map<String, ChannelRole>, viewer: String, target: String): Boolean {
    val role = roles[target] ?: return false
    return target != viewer && role != ChannelRole.Owner && role != ChannelRole.Unknown
}

/**
 * 役割を変えられるか。owner / admin は誰でも（owner と不明を除く）、ほかのメンバーはゲストをメンバーにすることだけ
 * （Buzz の `may_change_role`）。
 */
fun canChangeRole(authority: ChannelAuthority, roles: Map<String, ChannelRole>, viewer: String, target: String): Boolean =
    if (authority.canManage) {
        isManageableTarget(roles, viewer, target)
    } else {
        authority.canAdd && target != viewer && roles[target] == ChannelRole.Guest
    }

fun canRemoveMember(authority: ChannelAuthority, roles: Map<String, ChannelRole>, viewer: String, target: String): Boolean =
    authority.canRemove && isManageableTarget(roles, viewer, target)

/** 管理者が付けられる役割。 */
val ASSIGNABLE_CHANNEL_ROLES = listOf(ChannelRole.Admin, ChannelRole.Member, ChannelRole.Guest)

/** 役割の変更の選択肢（今の役割を除く）。bot は member と同じ扱い（リレーは member の指定を bot として保存する）。 */
fun roleChoices(current: ChannelRole): List<ChannelRole> {
    val effective = if (current == ChannelRole.Bot) ChannelRole.Member else current
    return ASSIGNABLE_CHANNEL_ROLES.filter { it != effective }
}

/** [authority] で選べる役割の変更。owner / admin は [roleChoices]、ほかのメンバーはゲスト → メンバーだけ。 */
fun roleChoicesFor(authority: ChannelAuthority, current: ChannelRole): List<ChannelRole> = when {
    authority.canManage -> roleChoices(current)
    authority.canAdd && current == ChannelRole.Guest -> listOf(ChannelRole.Member)
    else -> emptyList()
}

private fun normalizedKey(pubkey: String): String {
    val key = pubkey.trim().lowercase()
    require(HEX64.matches(key)) { "pubkey must be 32-byte hex" }
    return key
}

/** メンバーを加える・役割を変える（kind 9000）。役割は admin / member / guest だけ。 */
fun buildChannelPutUser(groupId: String, pubkey: String, role: ChannelRole): GroupEventDraft {
    require(role in ASSIGNABLE_CHANNEL_ROLES) { "role must be admin, member or guest" }
    return GroupEventDraft(GROUP_PUT_USER_KIND, listOf(listOf("h", groupId), listOf("p", normalizedKey(pubkey)), listOf("role", role.wire!!)), "")
}

/** メンバーを外す（kind 9001）。 */
fun buildChannelRemoveUser(groupId: String, pubkey: String): GroupEventDraft =
    GroupEventDraft(GROUP_REMOVE_USER_KIND, listOf(listOf("h", groupId), listOf("p", normalizedKey(pubkey))), "")

/** アーカイブ・解除（kind 9002 の部分更新）。 */
fun buildChannelArchive(groupId: String, archived: Boolean): GroupEventDraft =
    GroupEventDraft(GROUP_EDIT_METADATA_KIND, listOf(listOf("h", groupId), listOf("archived", archived.toString())), "")

/** チャンネルの削除（kind 9008、owner だけ）。 */
fun buildChannelDelete(groupId: String): GroupEventDraft =
    GroupEventDraft(GROUP_DELETE_GROUP_KIND, listOf(listOf("h", groupId)), "")

/** 管理者による発言の削除（kind 9005）。Buzz は対象がちょうど1つでないと断るので1件ずつ送る。 */
fun buildAdminDeletion(groupId: String, eventId: String): GroupEventDraft =
    GroupEventDraft(GROUP_DELETE_EVENT_KIND, listOf(listOf("h", groupId), listOf("e", normalizedKey(eventId))), "")

/** 送った変更（null は外した）を、リレーの新しい 39002 が届くまで役割に重ねる。 */
fun applyPendingRoles(roles: Map<String, ChannelRole>, pending: Map<String, ChannelRole?>): Map<String, ChannelRole> {
    if (pending.isEmpty()) return roles
    val result = LinkedHashMap(roles)
    pending.forEach { (key, role) -> if (role == null) result.remove(key) else result[key] = role }
    return result
}

/** 役割の表示名。member は出さない。 */
fun channelRoleLabel(role: ChannelRole?): String? = when (role) {
    ChannelRole.Owner -> "オーナー"
    ChannelRole.Admin -> "管理者"
    ChannelRole.Guest -> "ゲスト"
    ChannelRole.Bot -> "ボット"
    else -> null
}

/** リレーが「アーカイブ済み」として断った（Buzz の `channel is archived`。`not archived` は含めない）。 */
fun isChannelArchivedRejection(reason: String): Boolean = "channel is archived" in reason.lowercase()

/**
 * 拒否理由でアーカイブ済みと分かったときの、表示に使うグループ情報。変えないときは null。
 * Buzz は通話（huddle）が終わるとチャンネルをアーカイブするが、kind 39000 を出し直さないため、
 * 39000 からはアーカイブ済みと分からない。
 */
fun archivedFromRejection(metadata: GroupMetadata?, reason: String): GroupMetadata? =
    metadata?.takeIf { !it.isArchived && isChannelArchivedRejection(reason) }?.copy(isArchived = true)

/**
 * 管理の命令（チャンネル・コミュニティ）の拒否理由を表示用の文にする（docs/buzz-admin-design.md 3.6）。
 * 知らない文言は原文を添える。
 */
fun adminRejectionMessage(reason: String): String {
    val text = reason.trim()
    val lower = text.lowercase()
    return when {
        "only owner can grant admin role" in lower -> "管理者にできるのはオーナーだけです"
        "admins can only remove members" in lower -> "管理者が外せるのはメンバーだけです"
        "not authorized" in lower -> "権限がありません"
        "cannot remove yourself" in lower || "cannot change your own role" in lower -> "自分には使えません"
        "cannot remove the relay owner" in lower || "cannot change the relay owner" in lower || "cannot set role to owner" in lower -> "オーナーには使えません"
        "member not found" in lower -> "すでにメンバーではありません"
        isChannelArchivedRejection(lower) -> "アーカイブ済みのチャンネルです"
        "temporarily unavailable" in lower -> "一時的に変更できません。しばらくしてからお試しください"
        lower.startsWith("auth-required:") || lower.startsWith("restricted:") -> "ログインできていないか、権限がありません"
        text.isEmpty() -> "受け付けられませんでした"
        else -> "受け付けられませんでした（$text）"
    }
}
