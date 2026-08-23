package com.nostr.torinos.engagement

import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction

data class NoteEngagementState(
    val reactionCount: Int = 0,
    val likeReactionCount: Int = 0,
    val customReactions: List<CustomReaction> = emptyList(),
    val unicodeReactions: List<UnicodeReaction> = emptyList(),
    val ownLikeEventId: String? = null,
    val ownEmojiReactionEventIds: Map<String, String> = emptyMap(),
    val repostCount: Int = 0,
    val ownRepostEventId: String? = null,
    val pendingOperations: Map<EngagementSlot, PendingEngagementOperation> = emptyMap(),
)

enum class EngagementSlot {
    Reaction,
    Repost,
}

data class EngagementOperationId(val value: String)

sealed interface EngagementRequest {
    val slot: EngagementSlot

    data object AddLike : EngagementRequest {
        override val slot = EngagementSlot.Reaction
    }

    data class AddEmoji(val option: ReactionOption) : EngagementRequest {
        override val slot = EngagementSlot.Reaction
    }

    data object RemoveLike : EngagementRequest {
        override val slot = EngagementSlot.Reaction
    }

    data class RemoveEmoji(val option: ReactionOption) : EngagementRequest {
        override val slot = EngagementSlot.Reaction
    }

    data object AddRepost : EngagementRequest {
        override val slot = EngagementSlot.Repost
    }

    data object RemoveRepost : EngagementRequest {
        override val slot = EngagementSlot.Repost
    }
}

data class EngagementDelta(
    val reactionCount: Int = 0,
    val likeReactionCount: Int = 0,
    val repostCount: Int = 0,
    val emojiOption: ReactionOption? = null,
    val emojiCount: Int = 0,
)

data class PendingEngagementOperation(
    val id: EngagementOperationId,
    val request: EngagementRequest,
    val removedEventId: String? = null,
    val appliedDelta: EngagementDelta,
)

sealed interface EngagementAction {
    data class Begin(
        val operationId: EngagementOperationId,
        val request: EngagementRequest,
    ) : EngagementAction

    data class Commit(
        val operationId: EngagementOperationId,
        val publishedEventId: String,
    ) : EngagementAction

    data class Rollback(
        val operationId: EngagementOperationId,
    ) : EngagementAction
}

val NoteEngagementState.hasOwnReaction: Boolean
    get() = ownLikeEventId != null ||
        ownEmojiReactionEventIds.isNotEmpty() ||
        pendingOperations[EngagementSlot.Reaction]?.request.let {
            it is EngagementRequest.AddLike || it is EngagementRequest.AddEmoji
        }

val NoteEngagementState.isRepostedByMe: Boolean
    get() = ownRepostEventId != null ||
        pendingOperations[EngagementSlot.Repost]?.request is EngagementRequest.AddRepost

val NoteEngagementState.isReactionPending: Boolean
    get() = EngagementSlot.Reaction in pendingOperations

val NoteEngagementState.isRepostPending: Boolean
    get() = EngagementSlot.Repost in pendingOperations

val NoteEngagementState.displayOwnEmojiReactionEventIds: Map<String, String>
    get() {
        val pending = pendingOperations[EngagementSlot.Reaction] ?: return ownEmojiReactionEventIds
        val request = pending.request as? EngagementRequest.AddEmoji ?: return ownEmojiReactionEventIds
        return ownEmojiReactionEventIds + (request.option.key to pending.id.value)
    }
