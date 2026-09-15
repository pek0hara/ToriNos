package com.nostr.torinos

import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.ui.post.PostMemoData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ComposerCoordinatorTest {
    @Test
    fun dismissPostDiscardsDraftAndClearsReplyAndQuoteContext() {
        val coordinator = ComposerCoordinator()
        val draft = memo("draft")
        coordinator.localDraft = draft
        coordinator.prepareReply(event(), "preview", NoteContext.Timeline)
        coordinator.quoteToId = "quote"
        coordinator.showPostSheet = true

        coordinator.dismissPost()

        assertNull(coordinator.localDraft)
        assertFalse(coordinator.showPostSheet)
        assertNull(coordinator.replyTarget)
        assertNull(coordinator.quoteToId)
        assertEquals(NoteContext.Timeline, coordinator.replyNoteContext)
    }

    @Test
    fun staleReplyResolutionCannotBecomeCurrentAgain() {
        val tracker = ReplyResolutionTracker()
        val first = tracker.begin()
        tracker.invalidate()
        val second = tracker.begin()

        assertFalse(tracker.isCurrent(first))
        assertEquals(true, tracker.isCurrent(second))
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

    private fun event() = NostrEvent(
        id = "event",
        pubkey = "author",
        createdAt = 0L,
        kind = 1,
        tags = emptyList(),
        content = "content",
        sig = "sig",
    )
}
