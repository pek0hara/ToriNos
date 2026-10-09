package com.nostr.torinos.journal

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.isSupportedTimelineComment
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.network.ReactionEventStore

/** ジャーナルに並ぶアクティビティの種類。宣言順がフィルターの並び順になる。 */
enum class JournalActivityKind {
    Post,
    Repost,
    Reply,
    Like,
    ReceivedLike,
}

/**
 * ジャーナルに載せる人。自分のジャーナルでは自分と追加したメンバー、他人のジャーナルではその人だけ。
 * 種類の判定は人ごとに行う（AがBの投稿にいいねすれば、Aの「したいいね」でBの「もらったいいね」）。
 */
data class JournalMembers(val pubkeys: Set<String>) {
    constructor(vararg pubkeys: String) : this(pubkeys.toSet())
}

internal fun availableJournalKinds(): Set<JournalActivityKind> = JournalActivityKind.entries.toSet()

internal fun defaultJournalKinds(): Set<JournalActivityKind> =
    setOf(JournalActivityKind.Post, JournalActivityKind.Repost, JournalActivityKind.Reply)

/** 何も選んでいなければ既定の種類にする。 */
internal fun effectiveJournalKinds(selected: Set<JournalActivityKind>): Set<JournalActivityKind> =
    (selected intersect availableJournalKinds()).ifEmpty { defaultJournalKinds() }

/** 取得・表示・件数・日付移動が共有する唯一の種類判定。メンバーごとの種類を返し、該当しないメンバーは含めない。 */
internal object JournalActivityClassifier {
    fun classify(
        event: NostrEvent,
        members: JournalMembers,
        isAddressedTo: (NostrEvent, String) -> Boolean = ReactionEventStore::isAddressedTo,
    ): Map<String, Set<JournalActivityKind>> {
        val result = mutableMapOf<String, MutableSet<JournalActivityKind>>()
        fun add(pubkey: String, kind: JournalActivityKind) {
            result.getOrPut(pubkey) { mutableSetOf() } += kind
        }
        val author = event.pubkey.takeIf { it in members.pubkeys }
        when (event.kind) {
            1 -> if (author != null) {
                add(author, if (event.replyTargetId() != null) JournalActivityKind.Reply else JournalActivityKind.Post)
            }
            COMMENT_EVENT_KIND ->
                if (author != null && event.isSupportedTimelineComment()) add(author, JournalActivityKind.Reply)
            6, 16 -> if (author != null) add(author, JournalActivityKind.Repost)
            7 -> {
                if (author != null) add(author, JournalActivityKind.Like)
                members.pubkeys
                    .filter { event.isReceivedLikeFor(it, isAddressedTo) }
                    .forEach { add(it, JournalActivityKind.ReceivedLike) }
            }
        }
        return result
    }
}

/** もらったいいね。`-`（よくないね）は含めない。 */
internal fun NostrEvent.isReceivedLikeForJournal(pubkey: String): Boolean =
    isReceivedLikeFor(pubkey, ReactionEventStore::isAddressedTo)

private fun NostrEvent.isReceivedLikeFor(
    pubkey: String,
    isAddressedTo: (NostrEvent, String) -> Boolean,
): Boolean = kind == 7 && content.trim() != "-" && isAddressedTo(this, pubkey)
