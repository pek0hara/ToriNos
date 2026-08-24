package com.nostr.torinos.ui.feed

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
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.flow.StateFlow

/** Feed画面のFacade。履歴・ライブ・gap fill・集約の所有者はFeedController。 */
class FeedViewModel(
    accountSession: AccountSession? = null,
    authorPubkey: String? = null,
    authorPubkeys: List<String>? = authorPubkey?.let { listOf(it) },
    relayUrl: String? = null,
    autoStart: Boolean = true,
    includeRepostsInFeed: Boolean = false,
    includeRepliesInFeed: Boolean = false,
    hashtag: String? = null,
    filterMutedUsers: Boolean = true,
) : SafeViewModel() {
    data class UiState(
        val events: List<NostrEvent> = emptyList(),
        val profiles: Map<String, NostrProfile> = emptyMap(),
        val reactionCounts: Map<String, Int> = emptyMap(),
        val likeReactionCounts: Map<String, Int> = emptyMap(),
        val customReactions: Map<String, List<CustomReaction>> = emptyMap(),
        val unicodeReactions: Map<String, List<UnicodeReaction>> = emptyMap(),
        val replyCounts: Map<String, Int> = emptyMap(),
        val replies: Map<String, List<NostrEvent>> = emptyMap(),
        val repostCounts: Map<String, Int> = emptyMap(),
        val quotedEvents: Map<String, NostrEvent> = emptyMap(),
        val repostedByPubkeys: Map<String, String> = emptyMap(),
        val likedReactions: Map<String, String> = emptyMap(),
        val ownEmojiReactionEventIds: Map<String, Map<String, String>> = emptyMap(),
        val repostedEvents: Map<String, String> = emptyMap(),
        val pendingEngagementOperations: Map<String, Map<EngagementSlot, PendingEngagementOperation>> = emptyMap(),
        val engagementError: String? = null,
        val canLoadMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        val isInitialLoad: Boolean = true,
        val isRefreshing: Boolean = false,
    ) {
        fun isLiked(eventId: String): Boolean = likedReactions.containsKey(eventId) ||
            pendingEngagementOperations[eventId]?.get(EngagementSlot.Reaction)?.request is EngagementRequest.AddLike
        fun displayOwnEmojiReactionEventIds(eventId: String): Map<String, String> =
            noteEngagement(eventId).displayOwnEmojiReactionEventIds
        fun isReposted(eventId: String): Boolean = noteEngagement(eventId).isRepostedByMe
    }

    private val controller = FeedController(
        accountSession, authorPubkey, authorPubkeys, relayUrl, autoStart,
        includeRepostsInFeed, includeRepliesInFeed, hashtag, filterMutedUsers, viewModelScope,
    )
    val state: StateFlow<UiState> = controller.state

    fun injectProfile(pubkey: String, profile: NostrProfile) = controller.injectProfile(pubkey, profile)
    fun deleteEvent(eventId: String) = controller.deleteEvent(eventId)
    fun consumeEngagementError() = controller.consumeEngagementError()
    fun react(eventId: String, eventPubkey: String) = controller.react(eventId, eventPubkey)
    fun unreact(eventId: String) = controller.unreact(eventId)
    fun reactWithEmoji(eventId: String, eventPubkey: String, option: ReactionOption) =
        controller.reactWithEmoji(eventId, eventPubkey, option)
    fun unreactWithEmoji(eventId: String, option: ReactionOption) =
        controller.unreactWithEmoji(eventId, option)
    fun repost(event: NostrEvent) = controller.repost(event)
    fun unrepost(eventId: String) = controller.unrepost(eventId)
    fun reportEvent(event: NostrEvent, reason: String, detail: String) =
        controller.reportEvent(event, reason, detail)
    fun loadMore() = controller.loadMore()
    fun refresh() = controller.refresh()
    fun startSubscriptions() = controller.startSubscriptions()
    fun stopSubscriptions(clearRefreshing: Boolean = true) = controller.stopSubscriptions(clearRefreshing)

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
