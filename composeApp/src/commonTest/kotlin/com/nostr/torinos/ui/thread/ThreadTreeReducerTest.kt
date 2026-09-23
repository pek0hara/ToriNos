package com.nostr.torinos.ui.thread

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ThreadTreeReducerTest {
    @Test
    fun directRepliesAreDeduplicatedAndDeterministicallySorted() {
        val later = event("b", createdAt = 20)
        val earlier = event("a", createdAt = 10)

        val afterLater = ThreadTreeReducer.reduce(
            ThreadTreeState(),
            ThreadTreeAction.DirectReplyReceived(later),
        )
        val sorted = ThreadTreeReducer.reduce(
            afterLater,
            ThreadTreeAction.DirectReplyReceived(earlier),
        )
        val duplicate = ThreadTreeReducer.reduce(
            sorted,
            ThreadTreeAction.DirectReplyReceived(earlier),
        )

        assertEquals(listOf("a", "b"), sorted.replies.map { it.id })
        assertSame(sorted, duplicate)
    }

    @Test
    fun descendantReplyCountChangesOnlyForANewEvent() {
        val reply = event("reply", createdAt = 10)
        val first = ThreadTreeReducer.reduce(
            ThreadTreeState(),
            ThreadTreeAction.DescendantReplyReceived("parent", reply),
        )
        val duplicate = ThreadTreeReducer.reduce(
            first,
            ThreadTreeAction.DescendantReplyReceived("parent", reply),
        )

        assertEquals(1, first.replyCounts["parent"])
        assertEquals(listOf(reply), first.repliesByEventId["parent"])
        assertSame(first, duplicate)
    }

    @Test
    fun removingAReplyPromotesItsOrphanedDescendantsToTopLevel() {
        val parent = event("parent", createdAt = 10)
        val child = event("child", createdAt = 20)
        val withParent = ThreadTreeReducer.reduce(
            ThreadTreeState(),
            ThreadTreeAction.DirectReplyReceived(parent),
        )
        val withChild = ThreadTreeReducer.reduce(
            withParent,
            ThreadTreeAction.DescendantReplyReceived("parent", child),
        )

        val result = ThreadTreeReducer.reduce(
            withChild,
            ThreadTreeAction.ReplyRemoved("root", "parent"),
        )

        assertEquals(listOf("child"), result.replies.map { it.id })
        assertEquals(emptyList(), result.repliesByEventId["parent"] ?: emptyList())
    }

    @Test
    fun publishedReplyUsesExistingAggregateAsTheCountBase() {
        val state = ThreadTreeState(replyCounts = mapOf("root" to 4))

        val result = ThreadTreeReducer.reduce(
            state,
            ThreadTreeAction.ReplyPublished("root", event("published", createdAt = 10)),
        )

        assertEquals(5, result.replyCounts["root"])
        assertEquals("published", result.replies.single().id)
    }

    private fun event(id: String, createdAt: Long) = NostrEvent(
        id = id,
        pubkey = "author-$id",
        createdAt = createdAt,
        kind = 1,
        tags = emptyList(),
        content = id,
        sig = "sig-$id",
    )
}
