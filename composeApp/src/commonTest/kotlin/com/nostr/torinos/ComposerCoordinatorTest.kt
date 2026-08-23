package com.nostr.torinos

import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.ui.post.PostMemoData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class ComposerCoordinatorTest {
    @Test
    fun cancelPostKeepsDraftAndClearsReplyAndQuoteContext() {
        val coordinator = ComposerCoordinator()
        val draft = memo("draft")
        coordinator.prepareReply("event", "author", "preview", NoteContext.Timeline)
        coordinator.quoteToId = "quote"
        coordinator.showPostSheet = true

        coordinator.cancelPost(draft)

        assertSame(draft, coordinator.localDraft)
        assertFalse(coordinator.showPostSheet)
        assertNull(coordinator.replyToId)
        assertNull(coordinator.quoteToId)
        assertEquals(NoteContext.Timeline, coordinator.replyNoteContext)
    }

    @Test
    fun dismissPostClearsDraftAndEditingContext() {
        val coordinator = ComposerCoordinator()
        coordinator.localDraft = memo("draft")
        coordinator.selectedMemo = memo("memo")
        coordinator.showPostSheet = true

        coordinator.dismissPost()

        assertFalse(coordinator.showPostSheet)
        assertNull(coordinator.localDraft)
        assertNull(coordinator.selectedMemo)
    }

    private fun memo(text: String) = PostMemoData(
        text = text,
        imageUrls = emptyList(),
        replyToId = null,
        replyToPubkey = null,
        noteKind = 1,
        channelId = null,
        updatedAt = 0L,
    )
}
