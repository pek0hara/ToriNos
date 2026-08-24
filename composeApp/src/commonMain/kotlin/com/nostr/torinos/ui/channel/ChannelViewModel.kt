package com.nostr.torinos.ui.channel

import androidx.lifecycle.viewModelScope
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.PendingEngagementOperation
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.engagement.isRepostedByMe
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.flow.StateFlow

/** Channel画面のFacade。購読・ページング・投稿・編集・既読はChannelControllerが所有する。 */
class ChannelViewModel(
    channelId: String,
    relayUrl: String? = null,
    accountSession: AccountSession? = null,
) : SafeViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val channelMeta: ChannelMeta = ChannelMeta(),
            val channelOwnerPubkey: String? = null,
            val messages: List<NostrEvent> = emptyList(),
            val profiles: Map<String, NostrProfile> = emptyMap(),
            val replyCounts: Map<String, Int> = emptyMap(),
            val reactionCounts: Map<String, Int> = emptyMap(),
            val likeReactionCounts: Map<String, Int> = emptyMap(),
            val customReactions: Map<String, List<CustomReaction>> = emptyMap(),
            val unicodeReactions: Map<String, List<UnicodeReaction>> = emptyMap(),
            val repostCounts: Map<String, Int> = emptyMap(),
            val likedReactions: Map<String, String> = emptyMap(),
            val ownEmojiReactionEventIds: Map<String, Map<String, String>> = emptyMap(),
            val repostedEvents: Map<String, String> = emptyMap(),
            val pendingEngagementOperations: Map<String, Map<EngagementSlot, PendingEngagementOperation>> = emptyMap(),
            val engagementError: String? = null,
            val canLoadMore: Boolean = false,
            val draftText: String = "",
            val isPosting: Boolean = false,
            val postError: String? = null,
            val editDialog: EditThreadDialogState? = null,
        ) : UiState {
            fun isLiked(eventId: String): Boolean = likedReactions.containsKey(eventId) ||
                pendingEngagementOperations[eventId]?.get(EngagementSlot.Reaction)?.request is EngagementRequest.AddLike
            fun displayOwnEmojiReactionEventIds(eventId: String): Map<String, String> =
                noteEngagement(eventId).displayOwnEmojiReactionEventIds
            fun isReposted(eventId: String): Boolean = noteEngagement(eventId).isRepostedByMe
        }
    }

    data class EditThreadDialogState(
        val title: String = "",
        val description: String = "",
        val isSaving: Boolean = false,
        val error: String? = null,
    )

    private val controller = ChannelController(channelId, relayUrl, accountSession, viewModelScope)
    val state: StateFlow<UiState> = controller.state

    fun onDraftChange(text: String) = controller.onDraftChange(text)
    fun consumeEngagementError() = controller.consumeEngagementError()
    fun sendMessage() = controller.sendMessage()
    fun react(eventId: String, eventPubkey: String) = controller.react(eventId, eventPubkey)
    fun unreact(eventId: String) = controller.unreact(eventId)
    fun reactWithEmoji(eventId: String, eventPubkey: String, option: ReactionOption) =
        controller.reactWithEmoji(eventId, eventPubkey, option)
    fun unreactWithEmoji(eventId: String, option: ReactionOption) =
        controller.unreactWithEmoji(eventId, option)
    fun repost(event: NostrEvent) = controller.repost(event)
    fun unrepost(eventId: String) = controller.unrepost(eventId)
    fun showEditThreadDialog() = controller.showEditThreadDialog()
    fun dismissEditThreadDialog() = controller.dismissEditThreadDialog()
    fun onEditTitleChange(title: String) = controller.onEditTitleChange(title)
    fun onEditDescriptionChange(description: String) = controller.onEditDescriptionChange(description)
    fun saveThreadMeta() = controller.saveThreadMeta()
    fun loadMore() = controller.loadMore()

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
