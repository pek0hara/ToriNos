package com.nostr.torinos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.ui.post.PostMemoData

/** 投稿、返信、引用、メモ編集にまたがる一時状態の唯一の所有者。 */
internal class ComposerCoordinator {
    var showPostSheet by mutableStateOf(false)
    var showStatusComposer by mutableStateOf(false)
    var replyToId by mutableStateOf<String?>(null)
    var replyToPubkey by mutableStateOf<String?>(null)
    var replyToPreview by mutableStateOf<String?>(null)
    var quoteToId by mutableStateOf<String?>(null)
    var quoteToPubkey by mutableStateOf<String?>(null)
    var quoteToPreview by mutableStateOf<String?>(null)
    var replyNoteContext by mutableStateOf<NoteContext>(NoteContext.Timeline)
    var selectedMemo by mutableStateOf<PostMemoData?>(null)
    var selectedMemoDeleteAction by mutableStateOf<(() -> Unit)?>(null)
    var localDraft by mutableStateOf<PostMemoData?>(null)
    var memoRefreshTodayRequest by mutableStateOf(0)
    var journalToggleCalendarRequest by mutableStateOf(0)
    var journalShowCalendarRequest by mutableStateOf(0)
    var liveCreateRequest by mutableStateOf(0)
    var showKeySetup by mutableStateOf(false)
    var pendingKeyAction by mutableStateOf<PendingKeyAction?>(null)

    fun prepareQuote(event: NostrEvent) {
        clearPostContext(clearDraft = true)
        quoteToId = event.id
        quoteToPubkey = event.pubkey
        quoteToPreview = event.content.ifBlank { "投稿 ${event.id.take(8)}" }
    }

    fun prepareReply(
        eventId: String,
        authorPubkey: String,
        preview: String?,
        noteContext: NoteContext,
    ) {
        replyToId = eventId
        replyToPubkey = authorPubkey
        replyToPreview = preview
        replyNoteContext = noteContext
    }

    fun dismissPost() {
        clearPostContext(clearDraft = true)
        showPostSheet = false
    }

    fun cancelPost(draft: PostMemoData?) {
        if (selectedMemo == null) localDraft = draft
        clearPostContext(clearDraft = false)
        showPostSheet = false
    }

    fun dismissKeySetup() {
        pendingKeyAction = null
        clearPostContext(clearDraft = false)
        showKeySetup = false
    }

    fun clearPostContext(clearDraft: Boolean) {
        if (clearDraft) localDraft = null
        replyToId = null
        replyToPubkey = null
        replyToPreview = null
        quoteToId = null
        quoteToPubkey = null
        quoteToPreview = null
        replyNoteContext = NoteContext.Timeline
        selectedMemo = null
        selectedMemoDeleteAction = null
    }
}
