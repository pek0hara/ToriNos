package com.nostr.torinos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.model.toReplyTarget
import com.nostr.torinos.ui.post.PostMemoData

/** 投稿、返信、引用にまたがる一時状態の唯一の所有者。 */
internal class ComposerCoordinator {
    var showPostSheet by mutableStateOf(false)
    var showStatusComposer by mutableStateOf(false)
    var replyTarget by mutableStateOf<ReplyTarget?>(null)
    var replyToPreview by mutableStateOf<String?>(null)
    var quoteToId by mutableStateOf<String?>(null)
    var quoteToPubkey by mutableStateOf<String?>(null)
    var quoteToPreview by mutableStateOf<String?>(null)
    var replyNoteContext by mutableStateOf<NoteContext>(NoteContext.Timeline)
    var localDraft by mutableStateOf<PostMemoData?>(null)
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
        event: NostrEvent,
        preview: String?,
        noteContext: NoteContext,
    ): Boolean {
        val target = event.toReplyTarget(noteContext) ?: return false
        prepareReply(target, preview, noteContext)
        return true
    }

    fun prepareReply(
        target: ReplyTarget,
        preview: String?,
        noteContext: NoteContext,
    ) {
        quoteToId = null
        quoteToPubkey = null
        quoteToPreview = null
        replyTarget = target
        replyToPreview = preview
        replyNoteContext = noteContext
    }

    fun dismissPost() {
        clearPostContext(clearDraft = true)
        showPostSheet = false
    }

    fun dismissKeySetup() {
        pendingKeyAction = null
        clearPostContext(clearDraft = false)
        showKeySetup = false
    }

    fun clearPostContext(clearDraft: Boolean) {
        if (clearDraft) localDraft = null
        replyTarget = null
        replyToPreview = null
        quoteToId = null
        quoteToPubkey = null
        quoteToPreview = null
        replyNoteContext = NoteContext.Timeline
    }
}

internal class ReplyResolutionTracker {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun invalidate() {
        generation++
    }

    fun isCurrent(request: Long): Boolean = request == generation
}
