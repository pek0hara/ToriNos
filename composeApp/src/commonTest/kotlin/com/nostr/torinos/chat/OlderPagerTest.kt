package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OlderPagerTest {
    private fun ev(n: Int, at: Long) = NostrEvent("id$n", "p", at, 9, emptyList(), "", "s")

    @Test
    fun nothingToPageBeforeAnyEvent() {
        assertNull(OlderPager(pageSize = 3).until())
    }

    @Test
    fun cursorFollowsOldestFetchedEventEvenIfHidden() {
        val pager = OlderPager(pageSize = 3)
        pager.observe(listOf(ev(1, 100), ev(2, 90)))
        assertEquals(90L, pager.until())

        // 1ページ分すべてが画面に出ないもの（スレッドの返信など）でも、起点は進む。
        val fresh = pager.onPage(listOf(ev(2, 90), ev(3, 80), ev(4, 70)), allAnswered = true)

        assertEquals(listOf("id3", "id4"), fresh.map { it.id })
        assertEquals(70L, pager.until())
        assertTrue(pager.canLoadOlder)
    }

    @Test
    fun fullPageOfKnownEventsSkipsTheOldestSecond() {
        val pager = OlderPager(pageSize = 2)
        pager.observe(listOf(ev(1, 50), ev(2, 50)))

        pager.onPage(listOf(ev(1, 50), ev(2, 50)), allAnswered = true)

        assertEquals(49L, pager.until())
    }

    @Test
    fun shortAnsweredPageEndsPaging() {
        val pager = OlderPager(pageSize = 3)
        pager.observe(listOf(ev(1, 100)))
        pager.onPage(listOf(ev(2, 90)), allAnswered = true)
        assertFalse(pager.canLoadOlder)
    }

    @Test
    fun unansweredPageCanBeRetried() {
        val pager = OlderPager(pageSize = 3)
        pager.observe(listOf(ev(1, 100)))
        pager.onPage(emptyList(), allAnswered = false)
        assertTrue(pager.canLoadOlder)
        assertEquals(100L, pager.until())
    }
}
