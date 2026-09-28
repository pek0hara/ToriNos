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
 * ジャーナルの持ち主。
 * [isSelf]はボトムナビから開いた自分のジャーナルか。他人のジャーナルでは「したいいね」を扱わない。
 */
data class JournalOwner(val pubkey: String, val isSelf: Boolean)

internal fun availableJournalKinds(isSelf: Boolean): Set<JournalActivityKind> =
    if (isSelf) {
        JournalActivityKind.entries.toSet()
    } else {
        JournalActivityKind.entries.toSet() - JournalActivityKind.Like
    }

internal fun defaultJournalKinds(): Set<JournalActivityKind> =
    setOf(JournalActivityKind.Post, JournalActivityKind.Repost, JournalActivityKind.Reply)

/** 何も選んでいなければ既定の種類、選べない種類は除く。 */
internal fun effectiveJournalKinds(
    selected: Set<JournalActivityKind>,
    isSelf: Boolean,
): Set<JournalActivityKind> {
    val available = availableJournalKinds(isSelf)
    return (selected intersect available).ifEmpty { defaultJournalKinds() }
}

/** 取得・表示・件数・日付移動が共有する唯一の種類判定。 */
internal object JournalActivityClassifier {
    fun classify(
        event: NostrEvent,
        owner: JournalOwner,
        isAddressedTo: (NostrEvent, String) -> Boolean = ReactionEventStore::isAddressedTo,
    ): Set<JournalActivityKind> {
        val authored = event.pubkey == owner.pubkey
        return when (event.kind) {
            1 -> when {
                !authored -> emptySet()
                event.replyTargetId() != null -> setOf(JournalActivityKind.Reply)
                else -> setOf(JournalActivityKind.Post)
            }
            COMMENT_EVENT_KIND ->
                if (authored && event.isSupportedTimelineComment()) setOf(JournalActivityKind.Reply) else emptySet()
            6 -> if (authored) setOf(JournalActivityKind.Repost) else emptySet()
            7 -> buildSet {
                if (authored && owner.isSelf) add(JournalActivityKind.Like)
                if (event.isReceivedLikeFor(owner.pubkey, isAddressedTo)) add(JournalActivityKind.ReceivedLike)
            }
            else -> emptySet()
        }
    }
}

/** もらったいいね。`-`（よくないね）は含めない。 */
internal fun NostrEvent.isReceivedLikeForJournal(pubkey: String): Boolean =
    isReceivedLikeFor(pubkey, ReactionEventStore::isAddressedTo)

private fun NostrEvent.isReceivedLikeFor(
    pubkey: String,
    isAddressedTo: (NostrEvent, String) -> Boolean,
): Boolean = kind == 7 && content.trim() != "-" && isAddressedTo(this, pubkey)
