package com.nostr.torinos.journal

import kotlin.test.Test
import kotlin.test.assertEquals

class JournalInteractionsTest {
    private val alice = hexId(1)
    private val bob = hexId(2)

    @Test
    fun countsOutgoingByPTagAndIncomingBySender() {
        val events = listOf(
            reaction("l1", targetId = "t1", pubkey = OWNER, targetAuthor = alice),
            reply("r1", parentId = "p").copy(tags = listOf(listOf("e", "p", "", "reply"), listOf("p", bob))),
            reaction("l2", targetId = "t2", pubkey = bob, targetAuthor = OWNER),
        )

        assertEquals(listOf(bob to 2, alice to 1), rankJournalInteractions(events, OWNER))
    }

    @Test
    fun ignoresDislikesUnrelatedEventsAndSelf() {
        val events = listOf(
            reaction("d", targetId = "t", pubkey = alice, targetAuthor = OWNER, content = "-"),
            reaction("x", targetId = "t", pubkey = alice, targetAuthor = bob),
            reaction("s", targetId = "t", pubkey = OWNER, targetAuthor = OWNER),
            // 同じイベントが2度届いても1件。
            reaction("l", targetId = "t", pubkey = bob, targetAuthor = OWNER),
            reaction("l", targetId = "t", pubkey = bob, targetAuthor = OWNER),
        )

        assertEquals(listOf(bob to 1), rankJournalInteractions(events, OWNER))
    }
}
