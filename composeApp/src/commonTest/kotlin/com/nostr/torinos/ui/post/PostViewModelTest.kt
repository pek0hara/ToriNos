package com.nostr.torinos.ui.post

import kotlin.test.Test
import kotlin.test.assertEquals

class PostViewModelTest {
    @Test
    fun nextMemoUpdatedAtIsNewerThanThePreviousMemo() {
        assertEquals(101, nextMemoUpdatedAt(now = 100, previousUpdatedAt = 100))
        assertEquals(101, nextMemoUpdatedAt(now = 90, previousUpdatedAt = 100))
    }

    @Test
    fun nextMemoUpdatedAtUsesCurrentTimeForANewMemo() {
        assertEquals(100, nextMemoUpdatedAt(now = 100, previousUpdatedAt = null))
        assertEquals(100, nextMemoUpdatedAt(now = 100, previousUpdatedAt = 90))
    }
}
