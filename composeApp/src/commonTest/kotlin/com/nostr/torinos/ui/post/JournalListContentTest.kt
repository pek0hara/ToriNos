package com.nostr.torinos.ui.post

import kotlin.test.Test
import kotlin.test.assertEquals

class JournalListContentTest {
    @Test
    fun monthListWaitsForTheWholeMonth() {
        assertEquals(JournalListContent.Loading, content(isLoading = true))
        assertEquals(JournalListContent.Loading, content(isBackfilling = true))
        // 今日の分が先に届いていても、月の一覧は月の残りがそろうまで出さない（後から行が増えて跳ばないように）。
        assertEquals(JournalListContent.Loading, content(isBackfilling = true, hasEntries = true))
        assertEquals(JournalListContent.Empty, content())
        assertEquals(JournalListContent.Entries, content(hasEntries = true))
    }

    @Test
    fun dayViewShowsTheDayWithoutWaitingForTheMonth() {
        assertEquals(JournalListContent.Entries, content(isBackfilling = true, hasEntries = true, showCalendar = true))
        assertEquals(JournalListContent.Empty, content(isBackfilling = true, showCalendar = true))
        assertEquals(JournalListContent.Loading, content(isLoading = true, showCalendar = true))
    }

    @Test
    fun pullRefreshKeepsTheShownList() {
        assertEquals(JournalListContent.Entries, content(isLoading = true, isBackfilling = true, hasEntries = true, isPullRefreshing = true))
        assertEquals(JournalListContent.Entries, content(isLoading = true, hasEntries = true, showCalendar = true, isPullRefreshing = true))
        // 出している行がなければ、引っ張って更新していてもスケルトンを出す。
        assertEquals(JournalListContent.Loading, content(isLoading = true, isPullRefreshing = true))
    }

    @Test
    fun errorIsShownOnceLoadingEnds() {
        assertEquals(JournalListContent.Error, content(hasError = true))
        assertEquals(JournalListContent.Error, content(hasError = true, hasEntries = true))
    }

    private fun content(
        isLoading: Boolean = false,
        isBackfilling: Boolean = false,
        hasEntries: Boolean = false,
        showCalendar: Boolean = false,
        hasError: Boolean = false,
        isPullRefreshing: Boolean = false,
    ) = journalListContent(isLoading, isBackfilling, showCalendar, hasEntries, hasError, isPullRefreshing)
}
