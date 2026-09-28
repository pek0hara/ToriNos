package com.nostr.torinos.ui.post

import kotlin.test.Test
import kotlin.test.assertEquals

class DraftMemoRepositoryTest {
    @Test
    fun mergeReplacesAnEditedDraftWithTheLatestEvent() {
        val original = draft(eventId = "old-event", identifier = "memo-1", text = "変更前", createdAt = 100)
        val edited = draft(eventId = "new-event", identifier = "memo-1", text = "変更後", createdAt = 200)

        val result = mergeDraftMemos(listOf(original), listOf(edited))

        assertEquals(1, result.size)
        assertEquals("new-event", result.single().eventId)
        assertEquals("変更後", result.single().memo.text)
    }

    @Test
    fun mergeKeepsDraftsWithDifferentIdentifiersNewestFirst() {
        val first = draft(eventId = "event-1", identifier = "memo-1", text = "1件目", createdAt = 100)
        val second = draft(eventId = "event-2", identifier = "memo-2", text = "2件目", createdAt = 200)

        assertEquals(listOf("event-2", "event-1"), mergeDraftMemos(listOf(first), listOf(second)).map { it.eventId })
    }

    @Test
    fun mergeUsesTheLowestEventIdWhenTimestampsMatch() {
        val higherId = draft(eventId = "ff-event", identifier = "memo-1", text = "破棄される内容", createdAt = 100)
        val lowerId = draft(eventId = "00-event", identifier = "memo-1", text = "保持される内容", createdAt = 100)

        assertEquals("00-event", mergeDraftMemos(listOf(higherId), listOf(lowerId)).single().eventId)
    }

    @Test
    fun deletionReferencesTheEventAndItsAddress() {
        assertEquals(
            listOf(
                listOf("e", "event-1"),
                listOf("a", "$MEMO_EVENT_KIND:pubkey:memo-1"),
                listOf("k", MEMO_EVENT_KIND.toString()),
                listOf("client", "ToriNos"),
            ),
            draftDeletionTags(draft(eventId = "event-1", identifier = "memo-1", text = "", createdAt = 1)),
        )
        assertEquals(
            listOf(listOf("e", "event-2"), listOf("k", MEMO_EVENT_KIND.toString()), listOf("client", "ToriNos")),
            draftDeletionTags(draft(eventId = "event-2", identifier = null, text = "", createdAt = 1)),
        )
    }

    private fun draft(eventId: String, identifier: String?, text: String, createdAt: Long) = DraftMemo(
        eventId = eventId,
        pubkey = "pubkey",
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
}
