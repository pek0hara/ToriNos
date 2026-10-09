package com.nostr.torinos.ui.settings

import com.nostr.torinos.emoji.EmojiSetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiDiscoveryDisplayTest {
    @Test
    fun beforeTheFirstListLoadTheListShowsLoadingInsteadOfEmpty() {
        // タブの切り替え中や取得の開始前は、取得中でなくても「見つかりません」を出さない。
        assertEquals(
            EmojiDiscoveryListContent.Loading,
            emojiDiscoveryListContent(listLoaded = false, isListLoading = false, showRegisteredOnly = false, isEmpty = true),
        )
        assertEquals(
            EmojiDiscoveryListContent.Loading,
            emojiDiscoveryListContent(listLoaded = true, isListLoading = true, showRegisteredOnly = false, isEmpty = true),
        )
        assertEquals(
            EmojiDiscoveryListContent.Empty,
            emojiDiscoveryListContent(listLoaded = true, isListLoading = false, showRegisteredOnly = false, isEmpty = true),
        )
        assertEquals(
            EmojiDiscoveryListContent.Sets,
            emojiDiscoveryListContent(listLoaded = false, isListLoading = true, showRegisteredOnly = false, isEmpty = false),
        )
    }

    @Test
    fun registeredOnlyCanBeEmptyWithoutWaiting() {
        assertEquals(
            EmojiDiscoveryListContent.Empty,
            emojiDiscoveryListContent(listLoaded = false, isListLoading = true, showRegisteredOnly = true, isEmpty = true),
        )
    }

    @Test
    fun sortLabelStaysOnTheChosenOrderWhileCountsLoad() {
        assertEquals(EmojiSetSort.Adoption, displayedEmojiSort(EmojiSetSort.Adoption, canCountAdoption = true))
        assertEquals(EmojiSetSort.Newest, displayedEmojiSort(EmojiSetSort.Adoption, canCountAdoption = false))
        assertEquals(EmojiSetSort.Newest, displayedEmojiSort(EmojiSetSort.Newest, canCountAdoption = true))
    }

    @Test
    fun loadMoreIsOfferedOnlyWhenNothingIsFetchedAutomatically() {
        val pending = EmojiSetAddress("a".repeat(64), "x")
        assertFalse(shouldOfferMoreEmojiReferences(pendingReferences = 5, autoRequests = listOf(pending), isLoading = false))
        assertFalse(shouldOfferMoreEmojiReferences(pendingReferences = 5, autoRequests = emptyList(), isLoading = true))
        assertFalse(shouldOfferMoreEmojiReferences(pendingReferences = 0, autoRequests = emptyList(), isLoading = false))
        assertTrue(shouldOfferMoreEmojiReferences(pendingReferences = 5, autoRequests = emptyList(), isLoading = false))
    }
}
