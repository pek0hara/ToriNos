package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toReactionOption
import com.nostr.torinos.model.toUnicodeReaction

/**
 * 購読から届いたイベントをフィードの集約済み表示状態へ反映する純粋関数群。
 *
 * 対象イベントの判定と重複排除は購読を管理する側の責務とし、ここでは受け取った
 * イベントを一度だけ反映することを前提にする。
 */
internal object EngagementAccumulator {
    fun reaction(
        state: FeedViewModel.UiState,
        targetId: String,
        event: NostrEvent,
        isOwn: Boolean,
    ): FeedViewModel.UiState {
        val customReaction = event.toCustomReaction()
        val unicodeReaction = event.toUnicodeReaction()
        val reactionOption = event.toReactionOption()
        return state.copy(
            reactionCounts = state.reactionCounts +
                (targetId to (state.reactionCounts[targetId] ?: 0) + 1),
            likeReactionCounts = if (event.content.trim() == "+") {
                state.likeReactionCounts +
                    (targetId to (state.likeReactionCounts[targetId] ?: 0) + 1)
            } else {
                state.likeReactionCounts
            },
            customReactions = if (customReaction != null) {
                state.customReactions + (
                    targetId to state.customReactions[targetId].orEmpty().incrementedWith(customReaction)
                )
            } else {
                state.customReactions
            },
            unicodeReactions = if (unicodeReaction != null) {
                state.unicodeReactions + (
                    targetId to state.unicodeReactions[targetId].orEmpty()
                        .incrementedWithUnicodeReaction(unicodeReaction)
                )
            } else {
                state.unicodeReactions
            },
            likedReactions = if (
                isOwn && event.content.trim() == "+" && !state.likedReactions.containsKey(targetId)
            ) {
                state.likedReactions + (targetId to event.id)
            } else {
                state.likedReactions
            },
            ownEmojiReactionEventIds = if (isOwn && reactionOption != null) {
                state.ownEmojiReactionEventIds + (
                    targetId to state.ownEmojiReactionEventIds[targetId].orEmpty()
                        .plus(reactionOption.key to event.id)
                )
            } else {
                state.ownEmojiReactionEventIds
            },
        )
    }

    fun reply(
        state: FeedViewModel.UiState,
        targetId: String,
        event: NostrEvent,
    ): FeedViewModel.UiState {
        val replies = state.replies[targetId].orEmpty()
        val updatedReplies = if (replies.any { it.id == event.id }) {
            replies
        } else {
            (replies + event).sortedBy { it.createdAt }
        }
        return state.copy(
            replyCounts = state.replyCounts + (targetId to (state.replyCounts[targetId] ?: 0) + 1),
            replies = state.replies + (targetId to updatedReplies),
        )
    }

    fun repost(
        state: FeedViewModel.UiState,
        targetId: String,
        event: NostrEvent,
        isOwn: Boolean,
    ): FeedViewModel.UiState = state.copy(
        repostCounts = state.repostCounts + (targetId to (state.repostCounts[targetId] ?: 0) + 1),
        repostedEvents = if (isOwn && !state.repostedEvents.containsKey(targetId)) {
            state.repostedEvents + (targetId to event.id)
        } else {
            state.repostedEvents
        },
    )

    fun quoteReposts(
        state: FeedViewModel.UiState,
        targetIds: Collection<String>,
    ): FeedViewModel.UiState {
        if (targetIds.isEmpty()) return state
        val counts = state.repostCounts.toMutableMap()
        targetIds.forEach { targetId -> counts[targetId] = (counts[targetId] ?: 0) + 1 }
        return state.copy(repostCounts = counts)
    }

    fun reconcileOwnEngagement(
        state: FeedViewModel.UiState,
        ownPubkey: String,
        reactionEvents: Collection<NostrEvent>,
        repostEvents: Collection<NostrEvent>,
    ): FeedViewModel.UiState {
        var reconciled = state
        reactionEvents.filter { it.pubkey == ownPubkey }.forEach { event ->
            val targetId = event.targetId() ?: return@forEach
            val option = event.toReactionOption()
            reconciled = reconciled.copy(
                likedReactions = if (event.content.trim() == "+") {
                    reconciled.likedReactions + (targetId to event.id)
                } else {
                    reconciled.likedReactions
                },
                ownEmojiReactionEventIds = if (option != null) {
                    reconciled.ownEmojiReactionEventIds + (
                        targetId to reconciled.ownEmojiReactionEventIds[targetId].orEmpty()
                            .plus(option.key to event.id)
                    )
                } else {
                    reconciled.ownEmojiReactionEventIds
                },
            )
        }
        repostEvents.filter { it.pubkey == ownPubkey }.forEach { event ->
            val targetId = event.targetId() ?: return@forEach
            reconciled = reconciled.copy(
                repostedEvents = reconciled.repostedEvents + (targetId to event.id),
            )
        }
        return reconciled
    }

    private fun NostrEvent.targetId(): String? =
        tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
}
