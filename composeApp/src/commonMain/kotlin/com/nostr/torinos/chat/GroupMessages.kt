package com.nostr.torinos.chat

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.normalizeShortcode
import com.nostr.torinos.emoji.toEmojiTag
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.encodeNevent

/** 署名前のグループのイベント。 */
data class GroupEventDraft(val kind: Int, val tags: List<List<String>>, val content: String)

/** 返信先。[relayUrl] は返信先を受け取ったリレー（`q` タグと nevent に入れる）。 */
data class GroupReplyTarget(val event: NostrEvent, val relayUrl: String)

/** ToriNos がグループのリレーへ送ってよい kind。 */
internal val GROUP_PUBLISHABLE_KINDS = setOf(
    GROUP_CHAT_KIND, 7, 5, GROUP_JOIN_REQUEST_KIND, GROUP_LEAVE_REQUEST_KIND,
    GROUP_THREAD_KIND, GROUP_COMMENT_KIND,
    BUZZ_EDIT_KIND, BUZZ_FORUM_POST_KIND, BUZZ_FORUM_COMMENT_KIND,
    GROUP_CREATE_KIND, GROUP_EDIT_METADATA_KIND,
    BUZZ_TYPING_KIND,
    // 管理者の命令（docs/buzz-admin-design.md）: メンバーの追加・役割の変更、外す、発言の削除、グループの削除。
    GROUP_PUT_USER_KIND, GROUP_REMOVE_USER_KIND, GROUP_DELETE_EVENT_KIND, GROUP_DELETE_GROUP_KIND,
)

private const val PREVIOUS_PREFIX_LENGTH = 8

/**
 * kind 9 のチャットメッセージ（NIP-C7、NIP-29）。
 * 返信は `q` タグと本文先頭の `nostr:nevent1…`。[previous] は NIP-29 のタイムライン参照（イベントIDの先頭8文字）。
 */
fun buildGroupChatMessage(
    groupId: String,
    text: String,
    replyTo: GroupReplyTarget?,
    previous: List<String>,
): GroupEventDraft {
    require(text.isNotBlank()) { "message must not be blank" }
    val tags = buildList {
        add(listOf("h", groupId))
        if (replyTo != null) {
            add(listOf("q", replyTo.event.id, replyTo.relayUrl, replyTo.event.pubkey))
            add(listOf("p", replyTo.event.pubkey))
        }
        if (previous.isNotEmpty()) add(listOf("previous") + previous)
    }
    val content = if (replyTo == null) {
        text
    } else {
        val nevent = encodeNevent(replyTo.event.id, listOf(replyTo.relayUrl), replyTo.event.pubkey)
        "nostr:$nevent\n$text"
    }
    return GroupEventDraft(GROUP_CHAT_KIND, tags, content)
}

fun buildGroupJoinRequest(groupId: String, inviteCode: String?, reason: String): GroupEventDraft =
    GroupEventDraft(
        kind = GROUP_JOIN_REQUEST_KIND,
        tags = buildList {
            add(listOf("h", groupId))
            inviteCode?.takeIf { it.isNotBlank() }?.let { add(listOf("code", it)) }
        },
        content = reason,
    )

/** 参加要求（kind 9021）が `OK false` で返ったときの種類。 */
enum class JoinRejection {
    /** 既にメンバー（`duplicate:`）。 */
    AlreadyMember,
    /** 管理者の審査待ち。追加されるまで待つ。 */
    PendingReview,
    /** 参加できない（招待が必要、支払いが必要、拒否など）。理由をそのまま見せる。 */
    Refused,
}

private val JOIN_PENDING_WORDS = listOf("pending", "review", "approval", "approve", "waiting", "awaiting", "queued")

/**
 * NIP-29 では、まだメンバーに追加していない参加要求は `OK false` で返り、審査待ちか確定の拒否かはメッセージで伝える。
 * メッセージの書式は決まっていないので、審査待ちは語で見分ける。見分けられないものは拒否として理由を見せる。
 */
fun classifyJoinRejection(reason: String): JoinRejection {
    val text = reason.trim().lowercase()
    return when {
        text.startsWith("duplicate") -> JoinRejection.AlreadyMember
        JOIN_PENDING_WORDS.any { it in text } -> JoinRejection.PendingReview
        else -> JoinRejection.Refused
    }
}

fun buildGroupLeaveRequest(groupId: String): GroupEventDraft =
    GroupEventDraft(GROUP_LEAVE_REQUEST_KIND, listOf(listOf("h", groupId)), "")

/**
 * リアクション。カスタム絵文字は `:shortcode:` と `emoji` タグ（自分のセットの絵文字はフィードと同じく4要素目にセットのアドレス）。
 * Buzz は shortcode を正規化して検証し、他のクライアントも小文字で照合するので、小文字にそろえて送る。
 */
fun buildGroupReaction(groupId: String, target: NostrEvent, option: ReactionOption, flavor: GroupFlavor): GroupEventDraft {
    val base = listOf(
        listOf("h", groupId),
        listOf("e", target.id),
        listOf("p", target.pubkey),
        listOf("k", target.kind.toString()),
    )
    return when (option) {
        is ReactionOption.Unicode -> GroupEventDraft(kind = 7, tags = base, content = option.value)
        is ReactionOption.Custom -> {
            val shortcode = if (flavor == GroupFlavor.Buzz) {
                normalizeBuzzShortcode(option.shortcode) ?: throw IllegalArgumentException("Buzz で使えない絵文字です")
            } else {
                normalizeShortcode(option.shortcode)
            }
            GroupEventDraft(
                kind = 7,
                tags = base + listOf(CustomEmoji(shortcode, option.imageUrl.trim()).toEmojiTag(option.setAddress)),
                content = ":$shortcode:",
            )
        }
    }
}

fun buildGroupDeletion(groupId: String, eventIds: List<String>): GroupEventDraft =
    GroupEventDraft(5, listOf(listOf("h", groupId)) + eventIds.map { listOf("e", it) }, "")

/** kind 9 の返信先（`q` タグ）。 */
fun groupReplyTargetId(event: NostrEvent): String? =
    event.tags.firstOrNull { it.firstOrNull() == "q" }?.getOrNull(1)

/** 表示する本文。返信の先頭にある返信先の `nostr:nevent1…` の行は、引用として別に出すので除く。 */
fun groupMessageBody(event: NostrEvent): String {
    val replyTo = groupReplyTargetId(event) ?: return event.content
    val firstLine = event.content.substringBefore('\n')
    val isQuoteLine = firstLine.trim().let { line ->
        (line.startsWith("nostr:nevent1") || line.startsWith("nostr:note1")) &&
            com.nostr.torinos.model.decodeNostrEventReference(line.removePrefix("nostr:"))?.eventId == replyTo
    }
    return if (isQuoteLine) event.content.substringAfter('\n', missingDelimiterValue = "") else event.content
}

/** そのリレーで受け取った直近のイベントID。`previous` タグに使う（他のリレーのイベントは入れない）。 */
class PreviousReferenceWindow(private val capacity: Int = 50) {
    private val ids = ArrayDeque<String>()

    fun add(eventId: String) {
        val prefix = eventId.take(PREVIOUS_PREFIX_LENGTH)
        if (prefix.length < PREVIOUS_PREFIX_LENGTH) return
        ids.remove(prefix)
        ids.addLast(prefix)
        while (ids.size > capacity) ids.removeFirst()
    }

    /** 新しい順に [count] 件。 */
    fun pick(count: Int = 3): List<String> = ids.reversed().take(count)
}

enum class GroupPublishViolation { WrongRelay, MissingGroupTag, UnsupportedKind }

/**
 * グループのイベントを送る前の検査。グループのイベントはそのグループのリレーだけへ、`h` を1つ持って送る。
 * null なら送ってよい。
 */
fun groupPublishViolation(
    event: NostrEvent,
    groupId: String,
    targets: Set<String>,
    groupRelay: String,
): GroupPublishViolation? {
    if (event.kind !in GROUP_PUBLISHABLE_KINDS) return GroupPublishViolation.UnsupportedKind
    val hTags = event.tags.filter { it.firstOrNull() == "h" }
    if (hTags.size != 1 || hTags.single().getOrNull(1) != groupId) return GroupPublishViolation.MissingGroupTag
    if (targets != setOf(groupRelay)) return GroupPublishViolation.WrongRelay
    return null
}

// ---- Buzz ----

/**
 * Buzz のチャットメッセージ（kind 9）。返信は NIP-10 の `e` タグ: ルートへの直接の返信は `["e", root, "", "reply"]`、
 * 入れ子は `root` と `reply` の2つ（Buzz の `thread_tags`）。`previous` と `q` は付けない。
 */
fun buildBuzzMessage(groupId: String, text: String, threadRoot: NostrEvent?, parent: NostrEvent?): GroupEventDraft {
    require(text.isNotBlank()) { "message must not be blank" }
    return GroupEventDraft(GROUP_CHAT_KIND, listOf(listOf("h", groupId)) + buzzThreadTags(threadRoot, parent), text)
}

private fun buzzThreadTags(threadRoot: NostrEvent?, parent: NostrEvent?): List<List<String>> {
    if (threadRoot == null) return emptyList()
    val replyTo = parent ?: threadRoot
    return buildList {
        if (replyTo.id == threadRoot.id) {
            add(listOf("e", threadRoot.id, "", "reply"))
        } else {
            add(listOf("e", threadRoot.id, "", "root"))
            add(listOf("e", replyTo.id, "", "reply"))
        }
        add(listOf("p", replyTo.pubkey))
    }
}

/** Buzz のスレッドのルート。`root` があればそれ、無ければ `reply`。どちらも無ければトップレベル。 */
fun buzzThreadRootId(event: NostrEvent): String? {
    val eTags = event.tags.filter { it.firstOrNull() == "e" }
    return eTags.firstOrNull { it.getOrNull(3) == "root" }?.getOrNull(1)
        ?: eTags.firstOrNull { it.getOrNull(3) == "reply" }?.getOrNull(1)
}

/** Buzz のフォーラム投稿（kind 45001）。タイトルは本文の1行目として扱う。 */
fun buildForumPost(groupId: String, text: String): GroupEventDraft {
    require(text.isNotBlank()) { "post must not be blank" }
    return GroupEventDraft(BUZZ_FORUM_POST_KIND, listOf(listOf("h", groupId)), text)
}

/** Buzz のフォーラムのコメント（kind 45003）。 */
fun buildForumComment(groupId: String, text: String, post: NostrEvent, parent: NostrEvent? = null): GroupEventDraft {
    require(text.isNotBlank()) { "comment must not be blank" }
    return GroupEventDraft(BUZZ_FORUM_COMMENT_KIND, listOf(listOf("h", groupId)) + buzzThreadTags(post, parent), text)
}

/** Buzz の編集（kind 40003）。本文を丸ごと置き換える。 */
fun buildBuzzEdit(groupId: String, target: NostrEvent, text: String): GroupEventDraft {
    require(text.isNotBlank()) { "edit must not be blank" }
    return GroupEventDraft(BUZZ_EDIT_KIND, listOf(listOf("h", groupId), listOf("e", target.id)), text)
}

// ---- 標準の NIP-29 のスレッド（kind 11）とコメント（NIP-22 kind 1111） ----

fun buildGroupThread(groupId: String, title: String, text: String): GroupEventDraft {
    require(text.isNotBlank() || title.isNotBlank()) { "thread must not be blank" }
    return GroupEventDraft(
        GROUP_THREAD_KIND,
        buildList {
            add(listOf("h", groupId))
            if (title.isNotBlank()) add(listOf("title", title.trim()))
        },
        text,
    )
}

/** NIP-22 のコメント。大文字はルート、小文字は直接の親。 */
fun buildGroupComment(groupId: String, text: String, root: NostrEvent, parent: NostrEvent?, relayUrl: String): GroupEventDraft {
    require(text.isNotBlank()) { "comment must not be blank" }
    val direct = parent ?: root
    return GroupEventDraft(
        GROUP_COMMENT_KIND,
        listOf(
            listOf("h", groupId),
            listOf("E", root.id, relayUrl, root.pubkey), listOf("K", root.kind.toString()), listOf("P", root.pubkey),
            listOf("e", direct.id, relayUrl, direct.pubkey), listOf("k", direct.kind.toString()), listOf("p", direct.pubkey),
        ),
        text,
    )
}

/** フォーラム投稿・スレッドの見出し。`title` タグ、無ければ本文の1行目。 */
fun groupPostTitle(event: NostrEvent): String =
    event.tags.firstOrNull { it.firstOrNull() == "title" }?.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?: event.content.lineSequence().map { it.trim().trimStart('#', ' ') }.firstOrNull { it.isNotEmpty() }.orEmpty()

/** 添付を本文の後ろ（空行のあと）と `imeta` タグに足す。 */
fun GroupEventDraft.withAttachments(attachments: List<MessageAttachment>): GroupEventDraft {
    if (attachments.isEmpty()) return this
    val body = (listOf(content.trim()).filter { it.isNotEmpty() } + attachments.map { it.markdown }).joinToString("\n\n")
    return copy(content = body, tags = tags + attachments.map { it.imeta })
}
