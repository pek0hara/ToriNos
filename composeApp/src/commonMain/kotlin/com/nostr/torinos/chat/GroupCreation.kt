package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

// グループ・チャンネルの作成と、Buzz の DM を開く命令（docs/chat-create-design.md）。

/** NIP-29 の管理イベント: グループ情報の編集とグループの作成。 */
const val GROUP_EDIT_METADATA_KIND = 9002
const val GROUP_CREATE_KIND = 9007
/** Buzz の命令: DM を開く。結果は OK のメッセージで返る。 */
const val BUZZ_DM_OPEN_KIND = 41010

/** Buzz のチャンネル名の上限（`channels.name VARCHAR(255)`）。 */
const val MAX_GROUP_NAME_CHARS = 255
/** Buzz の DM は自分以外に8人まで。 */
const val MAX_DM_OTHERS = 8

enum class NewGroupType { Chat, Forum }

enum class NewGroupVisibility { Open, Private }

/** これから作るグループ。[groupId] は作る側が決める（Buzz は UUID v4、標準はランダムな英数字）。 */
data class NewGroupSpec(
    val groupId: String,
    val name: String,
    val about: String,
    val type: NewGroupType,
    val visibility: NewGroupVisibility,
    /** Buzz のラベル（`t`）。Solo は `buzz.solo`。 */
    val labels: List<String> = emptyList(),
    /** Buzz で、作成と同時に入れるメンバー（`p`）。 */
    val initialMembers: List<String> = emptyList(),
)

enum class GroupNameError { Empty, TooLong }

/** Buzz のリレーと同じ整え方: 先頭の `#` と空白、末尾の空白を除く。 */
fun canonicalGroupName(input: String): String =
    input.trimStart { it == '#' || it.isWhitespace() }.trimEnd()

fun groupNameError(input: String): GroupNameError? {
    val name = canonicalGroupName(input)
    return when {
        name.isEmpty() -> GroupNameError.Empty
        name.codePointLength() > MAX_GROUP_NAME_CHARS -> GroupNameError.TooLong
        else -> null
    }
}

/** サロゲートペアを1文字と数える（リレーは Unicode の文字数で数える）。 */
private fun String.codePointLength(): Int = count { !it.isLowSurrogate() }

private const val HEX_DIGITS = "0123456789abcdef"

/** UUID v4。Buzz のチャンネル ID はクライアントが決め、やり直しでも同じものを使う。 */
fun newBuzzChannelId(random: Random = Random.Default): String {
    val bytes = random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    return buildString {
        bytes.forEachIndexed { index, byte ->
            if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
            append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0f])
            append(HEX_DIGITS[byte.toInt() and 0x0f])
        }
    }
}

private const val GROUP_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/** 標準の NIP-29 のグループ ID（任意の文字列）。小文字の英数字12文字。 */
fun newStandardGroupId(random: Random = Random.Default): String =
    buildString { repeat(12) { append(GROUP_ID_ALPHABET[random.nextInt(GROUP_ID_ALPHABET.length)]) } }

private fun checkedName(spec: NewGroupSpec): String {
    require(groupNameError(spec.name) == null) { "invalid group name" }
    return canonicalGroupName(spec.name)
}

/**
 * Buzz のチャンネル作成（kind 9007）。タグの並びは Buzz のクライアント（`buildCreateChannelTags`）に合わせる。
 * 期限（`ttl`）と一覧からの除外（`unlisted`）は付けない。
 */
fun buildBuzzCreateChannel(spec: NewGroupSpec): GroupEventDraft {
    val name = checkedName(spec)
    val tags = buildList {
        add(listOf("h", spec.groupId))
        add(listOf("name", name))
        add(listOf("visibility", if (spec.visibility == NewGroupVisibility.Open) "open" else "private"))
        add(listOf("channel_type", if (spec.type == NewGroupType.Forum) "forum" else "stream"))
        spec.labels.distinct().forEach { add(listOf("t", it)) }
        spec.about.trim().takeIf { it.isNotEmpty() }?.let { add(listOf("about", it)) }
        spec.initialMembers.map(String::lowercase).distinct().forEach { add(listOf("p", it)) }
    }
    return GroupEventDraft(GROUP_CREATE_KIND, tags, "")
}

/** 標準の NIP-29 のグループ作成（kind 9007）。名前などは [buildStandardEditMetadata] で付ける。 */
fun buildStandardCreateGroup(groupId: String): GroupEventDraft {
    require(groupId.isNotBlank()) { "group id must not be blank" }
    return GroupEventDraft(GROUP_CREATE_KIND, listOf(listOf("h", groupId)), "")
}

/** 標準の NIP-29 のグループ情報の編集（kind 9002）。非公開は「メンバーだけが読める」+「招待制」。 */
fun buildStandardEditMetadata(spec: NewGroupSpec): GroupEventDraft {
    val name = checkedName(spec)
    val tags = buildList {
        add(listOf("h", spec.groupId))
        add(listOf("name", name))
        spec.about.trim().takeIf { it.isNotEmpty() }?.let { add(listOf("about", it)) }
        if (spec.visibility == NewGroupVisibility.Private) {
            add(listOf("private"))
            add(listOf("closed"))
        }
    }
    return GroupEventDraft(GROUP_EDIT_METADATA_KIND, tags, "")
}

/** 作成（kind 9007）が `OK false` で返ったときの種類。 */
enum class CreateRejection {
    /** 同じ名前のチャンネルがある。 */
    NameTaken,
    InvalidName,
    /** 同じ ID のグループが既にある。 */
    IdTaken,
    /** 作る権限が無い。 */
    NotAllowed,
    Other,
}

fun classifyCreateRejection(reason: String): CreateRejection {
    val text = reason.trim().lowercase()
    return when {
        text.startsWith("duplicate:") -> CreateRejection.IdTaken
        "named" in text && "already exists" in text -> CreateRejection.NameTaken
        // Buzz の編集の拒否は「invalid: actor not authorized for name/about/…」。名前の不正と取り違えない。
        "not authorized" in text -> CreateRejection.NotAllowed
        text.startsWith("invalid:") && "name" in text -> CreateRejection.InvalidName
        text.startsWith("restricted:") || text.startsWith("blocked:") -> CreateRejection.NotAllowed
        else -> CreateRejection.Other
    }
}

private val HEX64_KEY = Regex("^[0-9a-f]{64}$")

/**
 * Buzz の DM を開く命令（kind 41010）。`p` は自分以外の参加者（1〜8人）。同じ組み合わせなら既存の DM が返る。
 * `h` タグは無い。
 */
fun buildBuzzOpenDm(pubkeys: List<String>, me: String): GroupEventDraft {
    val normalized = pubkeys.map { it.trim().lowercase() }
    require(normalized.all(HEX64_KEY::matches)) { "pubkeys must be 32-byte hex" }
    val others = normalized.filter { it != me.lowercase() }.distinct()
    require(others.isNotEmpty()) { "a DM needs at least one other participant" }
    require(others.size <= MAX_DM_OTHERS) { "a DM may have at most $MAX_DM_OTHERS other participants" }
    return GroupEventDraft(BUZZ_DM_OPEN_KIND, others.map { listOf("p", it) }, "")
}

private val commandJson = Json { ignoreUnknownKeys = true }

/** Buzz の命令の結果。OK のメッセージ `response:{…}`（古いリレーは JSON だけ）から読む。 */
fun parseBuzzCommandResponse(okMessage: String): JsonObject? {
    val text = okMessage.trim()
    val body = if (text.startsWith(COMMAND_RESPONSE_PREFIX)) text.removePrefix(COMMAND_RESPONSE_PREFIX) else text
    if (!body.startsWith("{")) return null
    return runCatching { commandJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
}

private const val COMMAND_RESPONSE_PREFIX = "response:"

/** [BUZZ_DM_OPEN_KIND] の結果の DM チャンネル ID。 */
fun buzzDmChannelId(okMessage: String): String? =
    (parseBuzzCommandResponse(okMessage)?.get("channel_id") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }

/** ToriNos が送ってよい Buzz の命令（`h` タグを持たない）。コミュニティの直接追加（9030）は UI で使わないので入れない。 */
internal val BUZZ_COMMAND_KINDS = setOf(BUZZ_DM_OPEN_KIND, COMMUNITY_REMOVE_MEMBER_KIND, COMMUNITY_CHANGE_ROLE_KIND)

enum class BuzzCommandViolation { UnsupportedKind, NotBuzz, WrongRelay }

/** Buzz の命令を送る前の検査。そのコミュニティのリレーだけへ送る。null なら送ってよい。 */
fun buzzCommandViolation(
    event: NostrEvent,
    targets: Set<String>,
    communityRelay: String,
    flavor: GroupFlavor,
): BuzzCommandViolation? {
    if (event.kind !in BUZZ_COMMAND_KINDS) return BuzzCommandViolation.UnsupportedKind
    if (flavor != GroupFlavor.Buzz) return BuzzCommandViolation.NotBuzz
    if (targets != setOf(communityRelay)) return BuzzCommandViolation.WrongRelay
    return null
}
