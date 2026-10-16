package com.nostr.torinos.engagement

import com.nostr.torinos.model.*
import com.nostr.torinos.ui.search.SearchEngagementView
import kotlin.test.*

class EngagementProjectionTest {
    private fun reaction(id: String, author: String = "author") = NostrEvent(id, author, 100, 7, listOf(listOf("e", "target")), "+", "")

    @Test fun channelRepliesAreScopedAndRootIsNotAReply() {
        val store = EngagementStore()
        store.retain(this, setOf("target", "channel"))
        val reply = NostrEvent("reply", "author", 1, 42,
            listOf(listOf("e", "channel", "", "root"), listOf("e", "target", "", "reply")), "reply", "")
        val other = reply.copy(id = "other", tags = listOf(listOf("e", "other-channel", "", "root"), listOf("e", "target", "", "reply")))
        store.observe(reply, "relay")
        store.observe(other, "relay")
        store.observe(reply.copy(id = "root", tags = listOf(listOf("e", "channel", "", "root"))), "relay")
        val projected = projectEngagement(store.snapshot("target")!!, null, EngagementViewScope(NoteContext.Channel("channel")))
        assertEquals(1, projected.replyCount)
        assertEquals(setOf("reply"), projected.events.replies.keys)
        assertTrue(store.snapshot("channel")!!.replies.isEmpty())
    }

    @Test fun sourceScopeAppliesToConfirmedAndPendingValues() {
        val store = EngagementStore()
        store.retain(this, setOf("target"))
        store.observe(reaction("r"), "b")
        val pending = PendingEngagementOperation(EngagementOperationId("op"), EngagementRequest.AddLike,
            appliedDelta = EngagementDelta(reactionCount = 1, likeReactionCount = 1), deliveryRelayUrls = setOf("b"))
        val projected = projectEngagement(store.snapshot("target")!!, "me",
            EngagementViewScope(relayUrls = setOf("a")), mapOf(EngagementSlot.Reaction to pending))
        assertEquals(0, projected.state.reactionCount)
        assertTrue(projected.state.pendingOperations.isEmpty())
    }

    @Test fun searchAdmitsItsOwnResultsAndLocalCommitsWithoutImportingOtherFetches() {
        val store = EngagementStore()
        val view = SearchEngagementView(2, store, "session", "me")
        view.track("target")
        view.apply(reaction("accepted"))
        store.observe(reaction("external"), "relay")
        assertEquals(1, view.snapshot().getValue("target").reactionCount)
        store.observeLocal(reaction("local", "me"), "session")
        assertEquals(2, view.snapshot().getValue("target").reactionCount)
        store.observe(NostrEvent("delete", "me", 102, 5, listOf(listOf("e", "local")), "", ""), "relay")
        assertEquals(1, view.snapshot().getValue("target").reactionCount)
        view.clear()
        assertNull(store.snapshot("target"))
    }

    @Test fun deletionBeforeReactionIsNotRecountedAndSnapshotsStayStable() {
        val store = EngagementStore()
        val view = SearchEngagementView(2, store)
        view.track("target")
        store.observe(NostrEvent("delete", "author", 102, 5, listOf(listOf("e", "r")), "", ""), "relay")
        view.apply(reaction("r"))
        assertEquals(0, view.snapshot().getValue("target").reactionCount)
        val previous = view.snapshot()
        view.apply(reaction("r"))
        assertSame(previous, view.snapshot())
        view.clear()
    }
}
