package com.nostr.torinos.engagement

import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.decrementedWith
import com.nostr.torinos.model.decrementedWithUnicodeReaction
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction

object EngagementReducer {
    fun reduce(state: NoteEngagementState, action: EngagementAction): NoteEngagementState = when (action) {
        is EngagementAction.Begin -> begin(state, action)
        is EngagementAction.Commit -> commit(state, action)
        is EngagementAction.Rollback -> rollback(state, action)
    }

    private fun begin(state: NoteEngagementState, action: EngagementAction.Begin): NoteEngagementState {
        val request = action.request
        if (request.slot in state.pendingOperations) return state

        val transition = when (request) {
            EngagementRequest.AddLike -> {
                if (state.ownLikeEventId != null || state.ownEmojiReactionEventIds.isNotEmpty()) return state
                Transition(
                    state.copy(
                        reactionCount = state.reactionCount + 1,
                        likeReactionCount = state.likeReactionCount + 1,
                    ),
                    EngagementDelta(reactionCount = 1, likeReactionCount = 1),
                )
            }

            is EngagementRequest.AddEmoji -> {
                if (state.ownLikeEventId != null || state.ownEmojiReactionEventIds.isNotEmpty()) return state
                Transition(
                    state.applyEmojiDelta(request.option, 1).copy(
                        reactionCount = state.reactionCount + 1,
                    ),
                    EngagementDelta(reactionCount = 1, emojiOption = request.option, emojiCount = 1),
                )
            }

            EngagementRequest.RemoveLike -> {
                val removedId = state.ownLikeEventId ?: return state
                val reactionDelta = if (state.reactionCount > 0) -1 else 0
                val likeDelta = if (state.likeReactionCount > 0) -1 else 0
                Transition(
                    state.copy(
                        reactionCount = state.reactionCount + reactionDelta,
                        likeReactionCount = state.likeReactionCount + likeDelta,
                        ownLikeEventId = null,
                    ),
                    EngagementDelta(reactionCount = reactionDelta, likeReactionCount = likeDelta),
                    removedId,
                )
            }

            is EngagementRequest.RemoveEmoji -> {
                val removedId = state.ownEmojiReactionEventIds[request.option.key] ?: return state
                val reactionDelta = if (state.reactionCount > 0) -1 else 0
                val emojiDelta = if (state.emojiCount(request.option) > 0) -1 else 0
                Transition(
                    state.applyEmojiDelta(request.option, emojiDelta).copy(
                        reactionCount = state.reactionCount + reactionDelta,
                        ownEmojiReactionEventIds = state.ownEmojiReactionEventIds - request.option.key,
                    ),
                    EngagementDelta(
                        reactionCount = reactionDelta,
                        emojiOption = request.option,
                        emojiCount = emojiDelta,
                    ),
                    removedId,
                )
            }

            EngagementRequest.AddRepost -> {
                if (state.ownRepostEventId != null) return state
                Transition(
                    state.copy(repostCount = state.repostCount + 1),
                    EngagementDelta(repostCount = 1),
                )
            }

            EngagementRequest.RemoveRepost -> {
                val removedId = state.ownRepostEventId ?: return state
                val repostDelta = if (state.repostCount > 0) -1 else 0
                Transition(
                    state.copy(
                        repostCount = state.repostCount + repostDelta,
                        ownRepostEventId = null,
                    ),
                    EngagementDelta(repostCount = repostDelta),
                    removedId,
                )
            }
        }

        val pending = PendingEngagementOperation(
            id = action.operationId,
            request = request,
            removedEventId = transition.removedEventId,
            appliedDelta = transition.delta,
        )
        return transition.state.copy(
            pendingOperations = transition.state.pendingOperations + (request.slot to pending),
        )
    }

    private fun commit(state: NoteEngagementState, action: EngagementAction.Commit): NoteEngagementState {
        if (action.publishedEventId.isBlank()) return state
        val entry = state.pendingOperations.entries.firstOrNull { it.value.id == action.operationId } ?: return state
        val pending = entry.value
        val committed = when (pending.request) {
            EngagementRequest.AddLike -> state.copy(ownLikeEventId = action.publishedEventId)
            is EngagementRequest.AddEmoji -> state.copy(
                ownEmojiReactionEventIds = state.ownEmojiReactionEventIds +
                    (pending.request.option.key to action.publishedEventId),
            )
            EngagementRequest.AddRepost -> state.copy(ownRepostEventId = action.publishedEventId)
            EngagementRequest.RemoveLike,
            is EngagementRequest.RemoveEmoji,
            EngagementRequest.RemoveRepost,
            -> state
        }
        return committed.copy(pendingOperations = committed.pendingOperations - entry.key)
    }

    private fun rollback(state: NoteEngagementState, action: EngagementAction.Rollback): NoteEngagementState {
        val entry = state.pendingOperations.entries.firstOrNull { it.value.id == action.operationId } ?: return state
        val pending = entry.value
        val delta = pending.appliedDelta
        var rolledBack = state.applyEmojiDelta(delta.emojiOption, -delta.emojiCount).copy(
            reactionCount = maxOf(0, state.reactionCount - delta.reactionCount),
            likeReactionCount = maxOf(0, state.likeReactionCount - delta.likeReactionCount),
            repostCount = maxOf(0, state.repostCount - delta.repostCount),
        )
        rolledBack = when (val request = pending.request) {
            EngagementRequest.RemoveLike -> rolledBack.copy(ownLikeEventId = pending.removedEventId)
            is EngagementRequest.RemoveEmoji -> rolledBack.copy(
                ownEmojiReactionEventIds = pending.removedEventId?.let {
                    rolledBack.ownEmojiReactionEventIds + (request.option.key to it)
                } ?: rolledBack.ownEmojiReactionEventIds,
            )
            EngagementRequest.RemoveRepost -> rolledBack.copy(ownRepostEventId = pending.removedEventId)
            EngagementRequest.AddLike,
            is EngagementRequest.AddEmoji,
            EngagementRequest.AddRepost,
            -> rolledBack
        }
        return rolledBack.copy(pendingOperations = rolledBack.pendingOperations - entry.key)
    }

    private fun NoteEngagementState.applyEmojiDelta(
        option: ReactionOption?,
        delta: Int,
    ): NoteEngagementState {
        if (option == null || delta == 0) return this
        return when (option) {
            is ReactionOption.Custom -> copy(
                customReactions = if (delta > 0) {
                    customReactions.incrementedWith(
                        CustomReaction(option.shortcode.trim().trim(':'), option.imageUrl.trim()),
                    )
                } else {
                    customReactions.decrementedWith(option)
                },
            )
            is ReactionOption.Unicode -> copy(
                unicodeReactions = if (delta > 0) {
                    unicodeReactions.incrementedWithUnicodeReaction(UnicodeReaction(option.value.trim()))
                } else {
                    unicodeReactions.decrementedWithUnicodeReaction(option)
                },
            )
        }
    }

    private fun NoteEngagementState.emojiCount(option: ReactionOption): Int = when (option) {
        is ReactionOption.Custom -> customReactions.firstOrNull {
            it.shortcode == option.shortcode.trim().trim(':') && it.imageUrl == option.imageUrl.trim()
        }?.count ?: 0
        is ReactionOption.Unicode -> unicodeReactions.firstOrNull {
            it.content == option.value.trim()
        }?.count ?: 0
    }

    private data class Transition(
        val state: NoteEngagementState,
        val delta: EngagementDelta,
        val removedEventId: String? = null,
    )
}
