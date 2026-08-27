package com.nostr.torinos.ui.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryPageWindowTest {
    @Test
    fun sparseRelayOldEventDoesNotMoveCursorPastFirstPage() {
        val recentEvents = (71L..100L).toList()
        val monthsOldEvent = 1L

        val window = historyPageWindow(recentEvents + monthsOldEvent, pageSize = 30)

        assertTrue(window.hasMore)
        assertEquals(71L, window.revealOldestAt)
        assertEquals(70L, window.nextUntil)
    }

    @Test
    fun finalShortPageRevealsEveryRemainingEvent() {
        val window = historyPageWindow(listOf(30L, 20L, 10L), pageSize = 30)

        assertFalse(window.hasMore)
        assertEquals(10L, window.revealOldestAt)
        assertEquals(null, window.nextUntil)
    }

    @Test
    fun emptyPageStopsPagination() {
        assertEquals(
            HistoryPageWindow(hasMore = false, nextUntil = null, revealOldestAt = null),
            historyPageWindow(emptyList(), pageSize = 30),
        )
    }
}
