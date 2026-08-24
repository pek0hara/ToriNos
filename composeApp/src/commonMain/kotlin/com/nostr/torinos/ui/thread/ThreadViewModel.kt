package com.nostr.torinos.ui.thread

import androidx.lifecycle.viewModelScope
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.PendingEngagementOperation
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.engagement.isRepostedByMe
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.flow.StateFlow

/** Thread画面の公開APIだけを持つFacade。通信と集約はThreadControllerが所有する。 */
class ThreadViewModel(
    eventId: String,
    noteContext: NoteContext = NoteContext.Timeline,
    accountSession: AccountSession? = null,
) : SafeViewModel() {
    data class UiState(
        val root: NostrEvent? = null,
        val replies: List<NostrEvent> = emptyList(),
        val repliesByEventId: Map<String, List<NostrEvent>> = emptyMap(),
        val profiles: Map<String, NostrProfile> = emptyMap(),
        val replyCounts: Map<String, Int> = emptyMap(),
        val reactionCounts: Map<String, Int> = emptyMap(),
        val likeReactionCounts: Map<String, Int> = emptyMap(),
        val customReactions: Map<String, List<CustomReaction>> = emptyMap(),
        val unicodeReactions: Map<String, List<UnicodeReaction>> = emptyMap(),
        val reactionPubkeys: List<String> = emptyList(),
        val rootReactionsByPubkey: Map<String, NostrEvent> = emptyMap(),
        val repostPubkeys: List<String> = emptyList(),
        val quoteReposts: List<NostrEvent> = emptyList(),
        val repostCount: Int = 0,
        val likedReactions: Map<String, String> = emptyMap(),
        val ownEmojiReactionEventIds: Map<String, Map<String, String>> = emptyMap(),
        val ownRepostEventId: String? = null,
        val pendingEngagementOperations: Map<String, Map<EngagementSlot, PendingEngagementOperation>> = emptyMap(),
        val engagementError: String? = null,
        val isLoading: Boolean = true,
        val quotedEvents: Map<String, NostrEvent> = emptyMap(),
        val replyText: String = "",
        val isReplying: Boolean = false,
        val replyError: String? = null,
    ) {
        fun isLiked(eventId: String): Boolean = likedReactions.containsKey(eventId) ||
            pendingEngagementOperations[eventId]?.get(EngagementSlot.Reaction)?.request is EngagementRequest.AddLike

        fun displayOwnEmojiReactionEventIds(eventId: String): Map<String, String> =
            noteEngagement(eventId, rootEventId = null).displayOwnEmojiReactionEventIds

        fun isRootReposted(rootEventId: String): Boolean =
            noteEngagement(rootEventId, rootEventId).isRepostedByMe
    }

    private val controller = ThreadController(eventId, noteContext, accountSession, viewModelScope)
    val state: StateFlow<UiState> = controller.state

    fun onReplyTextChange(text: String) = controller.onReplyTextChange(text)
    fun consumeEngagementError() = controller.consumeEngagementError()
    fun submitReply() = controller.submitReply()
    fun react(eventId: String, eventPubkey: String) = controller.react(eventId, eventPubkey)
    fun unreact(eventId: String) = controller.unreact(eventId)
    fun reactWithEmoji(eventId: String, eventPubkey: String, option: ReactionOption) =
        controller.reactWithEmoji(eventId, eventPubkey, option)
    fun unreactWithEmoji(eventId: String, option: ReactionOption) =
        controller.unreactWithEmoji(eventId, option)
    fun repost(event: NostrEvent) = controller.repost(event)
    fun unrepost() = controller.unrepost()
    fun startSubscriptions() = controller.startSubscriptions()
    fun stopSubscriptions() = controller.stopSubscriptions()

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
