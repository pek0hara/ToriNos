package com.nostr.torinos.ui.channel

import com.nostr.torinos.emoji.CustomEmoji
import androidx.lifecycle.viewModelScope
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.EngagementSlot
import com.nostr.torinos.engagement.PendingEngagementOperation
import com.nostr.torinos.engagement.displayOwnEmojiReactionEventIds
import com.nostr.torinos.engagement.isRepostedByMe
import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.ChannelRelayContext
import com.nostr.torinos.network.RelayConnectionState
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
            val reactionEvents: Map<String, List<NostrEvent>> = emptyMap(),
            val repostCounts: Map<String, Int> = emptyMap(),
            val repostPubkeys: Map<String, List<String>> = emptyMap(),
            val likedReactions: Map<String, String> = emptyMap(),
            val ownEmojiReactionEventIds: Map<String, Map<String, String>> = emptyMap(),
            val repostedEvents: Map<String, String> = emptyMap(),
            val pendingEngagementOperations: Map<String, Map<EngagementSlot, PendingEngagementOperation>> = emptyMap(),
            val engagementError: String? = null,
            val canLoadMore: Boolean = false,
            val history: ChannelHistoryState = ChannelHistoryState(),
            val draftText: String = "",
            /** 下書きで使っているカスタム絵文字。選んだ画像を送信まで保持する（決定事項 D2）。 */
            val draftEmojis: List<CustomEmoji> = emptyList(),
            val isPosting: Boolean = false,
            val postError: String? = null,
            val editDialog: EditThreadDialogState? = null,
            /** 閲覧・投稿先の決定に使っているチャンネル固有の relay context(第16.13節)。 */
            val relayContext: ChannelRelayContext = ChannelRelayContext.EMPTY,
            /** kind 41 による推奨リレー変更で、購読先を切り替えている最中。 */
            val isRelayTransitioning: Boolean = false,
            /** 直近の投稿のリレー別結果(第16.9節)。 */
            val publishState: ChannelPublishUiState = ChannelPublishUiState.Idle,
            /** 閲覧先リレーの接続状態。現在の relay context の readRelays に絞ったもの。 */
            val relayStates: Map<String, RelayConnectionState> = emptyMap(),
            /** チャンネル情報画面の表示内容(FR-11)。kind 40 受信前は保存済みの状態から作る。 */
            val channelInfo: ChannelInfo? = null,
            /** 直前に非表示にしたメッセージ。「元に戻す」付きの snackbar を出す。 */
            val hiddenNoticeMessageId: String? = null,
            /** 閲覧先リレーのうち、購読を拒否(CLOSED)したものとその理由。 */
            val relayRefusals: Map<String, String> = emptyMap(),
            /** 非表示にしたメッセージのうち、読み込み済みのもの(管理画面用)。 */
            val hiddenMessages: List<NostrEvent> = emptyList(),
            /** 非表示にしたメッセージの総数(送信中を除く)。読み込み範囲外も含む。 */
            val hiddenCount: Int = 0,
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
        val picture: String = "",
        val candidateRelays: List<String> = emptyList(),
        val selectedRelays: Set<String> = emptySet(),
        val sourceEventId: String? = null,
        val sessionId: Long = 0,
        val isUploadingPicture: Boolean = false,
        val isSaving: Boolean = false,
        val error: String? = null,
    ) {
        val recommendedRelays: List<String> get() = candidateRelays.filter { it in selectedRelays }
        val pictureIsValid: Boolean get() = isValidChannelPictureUrl(picture)
        val canSave: Boolean get() = title.isNotBlank() && pictureIsValid &&
            selectedRelays.size <= 10 && !isUploadingPicture && !isSaving
    }

    private val controller = ChannelController(channelId, relayUrl, accountSession, viewModelScope)
    val state: StateFlow<UiState> = controller.state

    fun onDraftChange(text: String) = controller.onDraftChange(text)
    internal fun insertEmoji(selectionStart: Int, selectionEnd: Int, option: ReactionOption) =
        controller.insertEmoji(selectionStart, selectionEnd, option)
    fun consumeEngagementError() = controller.consumeEngagementError()
    fun sendMessage() = controller.sendMessage()
    fun deleteMessage(eventId: String) = controller.deleteMessage(eventId)
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
    fun onEditPictureUrlChange(picture: String) = controller.onEditPictureUrlChange(picture)
    fun onEditPictureSelected(bytes: ByteArray, mimeType: String) = controller.onEditPictureSelected(bytes, mimeType)
    fun onEditPictureRemoved() = controller.onEditPictureRemoved()
    fun onEditRelaySelectionChange(selected: Set<String>) = controller.onEditRelaySelectionChange(selected)
    fun addEditCustomRelay(raw: String): String? = controller.addEditCustomRelay(raw)
    fun saveThreadMeta() = controller.saveThreadMeta()
    fun loadMore() = controller.loadMore()

    fun loadHistoryGap() = controller.loadHistoryGap()
    fun jumpToPrevious() = controller.jumpToPrevious()
    fun jumpToLatest() = controller.jumpToLatest()
    fun retryMessages() = controller.retryMessages()
    fun consumeNavigation(sequence: Long) = controller.consumeNavigation(sequence)
    fun consumeHistoryNotice() = controller.consumeHistoryNotice()
    fun setAtLatest(value: Boolean) = controller.setAtLatest(value)
    fun flushReadingPosition() = controller.flushReadingPosition()
    fun onViewport(ids: Set<String>, anchorId: String?, offset: Int, savePosition: Boolean) =
        controller.onViewport(ids, anchorId, offset, savePosition)

    fun replyRelayHint(eventId: String): String? = controller.replyRelayHint(eventId)
    fun hideMessage(eventId: String) = controller.hideMessage(eventId)
    fun unhideMessage(eventId: String) = controller.unhideMessage(eventId)
    fun consumeHiddenNotice() = controller.consumeHiddenNotice()
    fun unhideAllMessages() = controller.unhideAllMessages()

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}

internal fun isValidChannelPictureUrl(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty()) return true
    if (!value.startsWith("https://", ignoreCase = true)) return false
    val authority = value.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.isNotBlank() && authority.none(Char::isWhitespace) && '@' !in authority
}
