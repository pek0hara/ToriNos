package com.nostr.torinos.journal

import com.nostr.torinos.engagement.EngagementReducer
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.isSupportedTimelineComment
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toReactionOption
import com.nostr.torinos.model.toUnicodeReaction
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.SubscriptionSignal

/**
 * 1投稿分のエンゲージメント。[summary]は楽観的更新でそのまま`NoteEngagementCoordinator`へ渡す。
 * 一覧（返信・リアクションしたイベント・リポストした人）は件数の表示とは別に持つ。
 */
data class JournalNoteEngagement(
    val summary: NoteEngagementState = NoteEngagementState(),
    val replyCount: Int = 0,
    val replies: List<NostrEvent> = emptyList(),
    val reactionEvents: List<NostrEvent> = emptyList(),
    val repostPubkeys: List<String> = emptyList(),
)

/** 取得中のイベントを投稿ごとに数える。通信は持たない。 */
internal class JournalEngagementAggregator(
    private val noteIds: Set<String>,
    private val ownPubkey: String?,
) {
    private val engagements = mutableMapOf<String, JournalNoteEngagement>()

    /** [event]を数え、変化した投稿のIDを返す。 */
    fun add(event: NostrEvent): Set<String> {
        if (event.kind == COMMENT_EVENT_KIND && !event.isSupportedTimelineComment()) return emptySet()
        val changed = mutableSetOf<String>()
        // 返信の対象はフィードと同じくNIP-10のマーカーで決め、リアクション・リポストは最後の`e`タグにする。
        val targetId = if (event.kind == 1 || event.kind == COMMENT_EVENT_KIND) {
            event.replyTargetId()
        } else {
            event.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
        }?.takeIf { it in noteIds }
        if (targetId != null) {
            when (event.kind) {
                7 -> update(targetId) { it.withReaction(event) }
                1, COMMENT_EVENT_KIND -> update(targetId) { it.withReply(event) }
                6 -> update(targetId) { it.withRepostBy(event.pubkey) }
                else -> return emptySet()
            }
            changed += targetId
        }
        if (event.kind == 1) {
            event.tags
                .filter { it.firstOrNull() == "q" }
                .mapNotNull { it.getOrNull(1) }
                .distinct()
                .filter { it in noteIds }
                .forEach { quotedId ->
                    update(quotedId) { it.withRepostBy(event.pubkey) }
                    changed += quotedId
                }
        }
        return changed
    }

    fun snapshot(ids: Set<String> = noteIds): Map<String, JournalNoteEngagement> =
        engagements.filterKeys { it in ids }

    private fun update(noteId: String, transform: (JournalNoteEngagement) -> JournalNoteEngagement) {
        engagements[noteId] = transform(engagements[noteId] ?: JournalNoteEngagement())
    }

    private fun JournalNoteEngagement.withReaction(event: NostrEvent): JournalNoteEngagement {
        var next = summary.copy(reactionCount = summary.reactionCount + 1)
        if (event.content.trim() == "+") next = next.copy(likeReactionCount = next.likeReactionCount + 1)
        event.toCustomReaction()?.let { next = next.copy(customReactions = next.customReactions.incrementedWith(it)) }
        event.toUnicodeReaction()?.let {
            next = next.copy(unicodeReactions = next.unicodeReactions.incrementedWithUnicodeReaction(it))
        }
        if (event.pubkey == ownPubkey) {
            if (event.content.trim() == "+" && next.ownLikeEventId == null) {
                next = next.copy(ownLikeEventId = event.id)
            }
            event.toReactionOption()?.let { option ->
                next = next.copy(ownEmojiReactionEventIds = next.ownEmojiReactionEventIds + (option.key to event.id))
            }
        }
        return copy(
            summary = next,
            reactionEvents = if (reactionEvents.any { it.id == event.id }) reactionEvents else reactionEvents + event,
        )
    }

    private fun JournalNoteEngagement.withReply(event: NostrEvent): JournalNoteEngagement = copy(
        replyCount = replyCount + 1,
        replies = (replies + event).distinctBy { it.id }.sortedBy { it.createdAt },
    )

    private fun JournalNoteEngagement.withRepostBy(pubkey: String): JournalNoteEngagement = copy(
        summary = summary.copy(repostCount = summary.repostCount + 1),
        repostPubkeys = (repostPubkeys + pubkey).distinct(),
    )
}

/** 途中経過は既存の値を減らさない。件数は大きい方、一覧は和集合にする。 */
internal fun Map<String, JournalNoteEngagement>.mergeProgressive(
    updates: Map<String, JournalNoteEngagement>,
): Map<String, JournalNoteEngagement> {
    if (updates.isEmpty()) return this
    val result = toMutableMap()
    updates.forEach { (noteId, update) ->
        val current = result[noteId] ?: JournalNoteEngagement()
        result[noteId] = current.mergedWith(update)
    }
    return result
}

/**
 * 全リレーが完了した取得結果で[noteIds]を置き換える。結果にない投稿は0件になる。
 * 送信中の楽観的更新は`EngagementReducer.rebase`で載せ直す。
 */
internal fun Map<String, JournalNoteEngagement>.replaceCompleted(
    noteIds: Set<String>,
    completed: Map<String, JournalNoteEngagement>,
): Map<String, JournalNoteEngagement> {
    val result = toMutableMap()
    noteIds.forEach { noteId ->
        val fetched = completed[noteId] ?: JournalNoteEngagement()
        val pending = result[noteId]?.summary?.pendingOperations.orEmpty()
        result[noteId] = if (pending.isEmpty()) {
            fetched
        } else {
            fetched.copy(summary = EngagementReducer.rebase(fetched.summary, pending))
        }
    }
    return result
}

private fun JournalNoteEngagement.mergedWith(update: JournalNoteEngagement): JournalNoteEngagement = copy(
    summary = summary.copy(
        reactionCount = maxOf(summary.reactionCount, update.summary.reactionCount),
        likeReactionCount = maxOf(summary.likeReactionCount, update.summary.likeReactionCount),
        customReactions = (summary.customReactions + update.summary.customReactions)
            .groupBy { it.shortcode to it.imageUrl }
            .values
            .map { same -> same.maxBy(CustomReaction::count) },
        unicodeReactions = (summary.unicodeReactions + update.summary.unicodeReactions)
            .groupBy { it.content }
            .values
            .map { same -> same.maxBy(UnicodeReaction::count) },
        ownLikeEventId = update.summary.ownLikeEventId ?: summary.ownLikeEventId,
        ownEmojiReactionEventIds = summary.ownEmojiReactionEventIds + update.summary.ownEmojiReactionEventIds,
        repostCount = maxOf(summary.repostCount, update.summary.repostCount),
    ),
    replyCount = maxOf(replyCount, update.replyCount),
    replies = (replies + update.replies).distinctBy { it.id }.sortedBy { it.createdAt },
    reactionEvents = (reactionEvents + update.reactionEvents).distinctBy { it.id },
    repostPubkeys = (repostPubkeys + update.repostPubkeys).distinct(),
)

/** タイムアウトせず、応答したすべてのリレーがEOSEを返したときだけ結果を確定する。 */
internal fun shouldCommitJournalFetch(completion: SubscriptionSignal.FetchCompleted): Boolean =
    !completion.timedOut &&
        completion.outcomes.isNotEmpty() &&
        completion.outcomes.values.all { it is RelayOutcome.Eose }
