package com.nostr.torinos.ui.status

import kotlin.test.Test
import kotlin.test.assertEquals

class StatusListContentTest {
    @Test
    fun notStartedOrLoadingShowsLoadingNotEmpty() {
        // タブの切り替え中（開始前）も「ステータスがありません」を出さない。
        assertEquals(StatusListContent.Loading, statusListContent(StatusLoadState.Idle, hasGroups = false, hasCategories = true))
        assertEquals(StatusListContent.Loading, statusListContent(StatusLoadState.Loading, hasGroups = true, hasCategories = true))
    }

    @Test
    fun readyShowsListOrEmpty() {
        assertEquals(StatusListContent.List, statusListContent(StatusLoadState.Ready, hasGroups = true, hasCategories = true))
        assertEquals(StatusListContent.Empty, statusListContent(StatusLoadState.Ready, hasGroups = false, hasCategories = true))
        assertEquals(StatusListContent.NoCategory, statusListContent(StatusLoadState.Ready, hasGroups = false, hasCategories = false))
    }

    @Test
    fun returningWithPreviousStatusesShowsThemWhileRefreshing() {
        assertEquals(StatusListContent.List, statusListContent(StatusLoadState.Idle, hasGroups = true, hasCategories = true))
    }
}
