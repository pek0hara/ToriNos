package com.nostr.torinos.ui.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchPageProgressTest {
    @Test
    fun interruptedPartialPageResumesAtTheSameBoundaryAndCountsRetriedEvents() {
        val page = SearchPageProgress()
        page.start(until = 100)
        page.observe("first", 80)
        page.interrupt()
        assertEquals(100, page.interruptedUntil)

        page.start(page.interruptedUntil)
        page.observe("first", 80) // 画面側では既存 ID と重複するが、再取得ページの件数に含める。
        page.observe("second", 70)
        page.observe("second", 70) // 同じページ内のリレー重複は数えない。
        assertEquals(SearchPageBatch(2, 70), page.complete())
        assertNull(page.interruptedUntil)
    }

    @Test
    fun newSearchDiscardsAnInterruptedBoundary() {
        val page = SearchPageProgress()
        page.start(until = 50)
        page.interrupt()
        page.reset()
        assertNull(page.interruptedUntil)
    }

    @Test
    fun partialOlderEventDoesNotSkipTheGapAfterRetry() {
        val page = SearchPageProgress()
        page.start(until = 100)
        page.observe("early-partial", 40)
        page.interrupt()
        page.start(page.interruptedUntil)
        page.observe("latest", 99)
        page.observe("next", 98)
        assertEquals(SearchPageBatch(2, 98), page.complete())
    }
}
