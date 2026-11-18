package com.nostr.torinos.chat

// 自分が作った（管理している）グループの編集（docs/chat-create-design.md 10章）。

/** Buzz: 参加した人の役割（kind 9002 の `join_role`）。ゲストは読むだけ。 */
enum class GroupJoinRole(val wire: String) { Member("member"), Guest("guest") }

/** 編集画面の内容。[joinRole] と [unlisted] は Buzz だけ（docs/buzz-admin-design.md 3.3）。 */
data class GroupEdit(
    val name: String,
    val about: String,
    val visibility: NewGroupVisibility,
    val joinRole: GroupJoinRole = GroupJoinRole.Member,
    val unlisted: Boolean = false,
)

/** いまのグループ情報から編集画面の初期値を作る。知らない `join_role` は Buzz と同じく guest とみなす。 */
fun groupEditOf(metadata: GroupMetadata): GroupEdit = GroupEdit(
    name = metadata.name,
    about = metadata.about,
    visibility = if (metadata.isPrivate) NewGroupVisibility.Private else NewGroupVisibility.Open,
    joinRole = when (metadata.joinRole) {
        null, GroupJoinRole.Member.wire -> GroupJoinRole.Member
        else -> GroupJoinRole.Guest
    },
    unlisted = metadata.isUnlisted,
)

/**
 * 編集の入口を出すか。作成者（kind 39000 の `P`）か管理者（kind 39001）に出す。DM は編集できない。
 * 表示のための判定で、実際の可否はリレーが決める（拒否されたら理由を見せる）。
 */
fun canEditGroup(metadata: GroupMetadata?, me: String?, admins: Set<String>): Boolean {
    if (metadata == null || me == null) return false
    if (metadata.layout == GroupLayout.Direct || metadata.layout == GroupLayout.Hidden) return false
    return metadata.creator == me || me in admins
}

private data class GroupEditChanges(
    val name: String?,
    val about: String?,
    val visibility: NewGroupVisibility?,
    val joinRole: GroupJoinRole? = null,
    val unlisted: Boolean? = null,
) {
    val isEmpty: Boolean get() = name == null && about == null && visibility == null && joinRole == null && unlisted == null
}

/** 変わった項目。名前が不正なら null。 */
private fun changesOf(current: GroupMetadata, edit: GroupEdit): GroupEditChanges? {
    if (groupNameError(edit.name) != null) return null
    val name = canonicalGroupName(edit.name)
    val about = edit.about.trim()
    val before = groupEditOf(current)
    return GroupEditChanges(
        name = name.takeIf { it != canonicalGroupName(current.name) },
        about = about.takeIf { it != current.about.trim() },
        visibility = edit.visibility.takeIf { it != before.visibility },
        joinRole = edit.joinRole.takeIf { it != before.joinRole },
        unlisted = edit.unlisted.takeIf { it != before.unlisted },
    )
}

/** 保存できる（名前が正しく、何かが変わっている）。標準の NIP-29 では公開範囲・参加時の役割・一覧の設定は変えない。 */
fun isGroupEditSavable(current: GroupMetadata, edit: GroupEdit, flavor: GroupFlavor = GroupFlavor.Buzz): Boolean =
    if (flavor == GroupFlavor.Buzz) changesOf(current, edit)?.isEmpty == false else buildStandardEditMetadata(current, edit) != null

/**
 * Buzz のチャンネル情報の編集（kind 9002）。Buzz では部分更新なので、**変えた項目だけ**を送る
 * （無いタグは「変更なし」。`t` を送るとラベルの集合が置き換わるので送らない）。変更が無ければ null。
 */
fun buildBuzzEditMetadata(current: GroupMetadata, edit: GroupEdit): GroupEventDraft? {
    val changes = changesOf(current, edit)?.takeIf { !it.isEmpty } ?: return null
    val tags = buildList {
        add(listOf("h", current.groupId))
        changes.name?.let { add(listOf("name", it)) }
        // 空文字で説明を消す。
        changes.about?.let { add(listOf("about", it)) }
        changes.visibility?.let { add(listOf("visibility", if (it == NewGroupVisibility.Open) "open" else "private")) }
        changes.joinRole?.let { add(listOf("join_role", it.wire)) }
        changes.unlisted?.let { add(listOf("unlisted", it.toString())) }
    }
    return GroupEventDraft(GROUP_EDIT_METADATA_KIND, tags, "")
}

/**
 * 標準の NIP-29 のグループ情報の編集（kind 9002）。「グループ情報の全フィールド」を送る決まりなので、
 * 変えない項目（画像、公開範囲のフラグ）も今の値のまま送る。公開範囲は変えない
 * （フラグの外し方が NIP-29 の本文に無く、リレーごとに違うため）。変更が無ければ null。
 */
fun buildStandardEditMetadata(current: GroupMetadata, edit: GroupEdit): GroupEventDraft? {
    val before = groupEditOf(current)
    val changes = changesOf(current, edit.copy(visibility = before.visibility, joinRole = before.joinRole, unlisted = before.unlisted))
        ?.takeIf { !it.isEmpty } ?: return null
    val tags = buildList {
        add(listOf("h", current.groupId))
        add(listOf("name", changes.name ?: canonicalGroupName(current.name)))
        (changes.about ?: current.about.trim()).takeIf { it.isNotEmpty() }?.let { add(listOf("about", it)) }
        current.picture.takeIf { it.isNotBlank() }?.let { add(listOf("picture", it)) }
        if (current.isPrivate) add(listOf("private"))
        if (current.isRestricted) add(listOf("restricted"))
        if (current.isHidden) add(listOf("hidden"))
        if (current.isClosed) add(listOf("closed"))
    }
    return GroupEventDraft(GROUP_EDIT_METADATA_KIND, tags, "")
}

/** 編集を反映したグループ情報（リレーの新しい kind 39000 が届くまでの表示用）。 */
fun GroupMetadata.withEdit(edit: GroupEdit, flavor: GroupFlavor): GroupMetadata {
    val renamed = copy(name = canonicalGroupName(edit.name), about = edit.about.trim())
    if (flavor != GroupFlavor.Buzz) return renamed
    val private = edit.visibility == NewGroupVisibility.Private
    // Buzz の 39000: 非公開か、参加した人がゲストなら restricted（メンバーだけが書ける）。
    return renamed.copy(
        isPrivate = private,
        isRestricted = private || edit.joinRole == GroupJoinRole.Guest,
        isClosed = private,
        joinRole = edit.joinRole.wire,
        isUnlisted = edit.unlisted,
    )
}
