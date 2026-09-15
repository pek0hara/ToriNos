package com.nostr.torinos.ui.post

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalViewModelTest {
    @Test
    fun receivedLikeAcceptsEveryReactionExceptDislikeAddressedToJournalOwner() {
        val receivedLike = reaction(content = "+", pTag = "journal-owner")
        val emojiReaction = reaction(content = "🔥", pTag = "journal-owner")
        val dislikeReaction = reaction(content = "-", pTag = "journal-owner")
        val someoneElsesLike = reaction(content = "+", pTag = "other-user")

        assertTrue(receivedLike.isReceivedLikeForJournal("journal-owner"))
        assertTrue(emojiReaction.isReceivedLikeForJournal("journal-owner"))
        assertFalse(dislikeReaction.isReceivedLikeForJournal("journal-owner"))
        assertFalse(someoneElsesLike.isReceivedLikeForJournal("journal-owner"))
    }

    @Test
    fun mergeJournalMemosReplacesAnEditedMemoWithTheLatestEvent() {
        val original = journalItem(
            eventId = "old-event",
            identifier = "memo-1",
            text = "変更前",
            createdAt = 100,
        )
        val edited = journalItem(
            eventId = "new-event",
            identifier = "memo-1",
            text = "変更後",
            createdAt = 200,
        )

        val result = mergeJournalMemos(listOf(original), listOf(edited))

        assertEquals(1, result.size)
        assertEquals("new-event", result.single().eventId)
        assertEquals("変更後", result.single().memo.text)
    }

    @Test
    fun mergeJournalMemosKeepsMemosWithDifferentIdentifiers() {
        val first = journalItem(
            eventId = "event-1",
            identifier = "memo-1",
            text = "1件目",
            createdAt = 100,
        )
        val second = journalItem(
            eventId = "event-2",
            identifier = "memo-2",
            text = "2件目",
            createdAt = 200,
        )

        val result = mergeJournalMemos(listOf(first), listOf(second))

        assertEquals(listOf("event-2", "event-1"), result.map { it.eventId })
    }

    @Test
    fun mergeJournalMemosUsesTheLowestEventIdWhenTimestampsMatch() {
        val higherId = journalItem(
            eventId = "ff-event",
            identifier = "memo-1",
            text = "破棄される内容",
            createdAt = 100,
        )
        val lowerId = journalItem(
            eventId = "00-event",
            identifier = "memo-1",
            text = "保持される内容",
            createdAt = 100,
        )

        val result = mergeJournalMemos(listOf(higherId), listOf(lowerId))

        assertEquals("00-event", result.single().eventId)
    }

    private fun journalItem(
        eventId: String,
        identifier: String?,
        text: String,
        createdAt: Long,
    ) = JournalItem(
        eventId = eventId,
        pubkey = "pubkey",
        tags = emptyList(),
        memo = PostMemoData(
            text = text,
            imageUrls = emptyList(),
            replyToId = null,
            replyToPubkey = null,
            noteKind = 1,
            channelId = null,
            updatedAt = createdAt,
            identifier = identifier,
        ),
        createdAt = createdAt,
    )

    private fun reaction(content: String, pTag: String) = NostrEvent(
        id = "reaction-$content-$pTag",
        pubkey = "reactor",
        createdAt = 100,
        kind = 7,
        tags = listOf(listOf("e", "target-note"), listOf("p", pTag)),
        content = content,
        sig = "signature",
    )
}
