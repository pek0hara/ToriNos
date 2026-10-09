package com.nostr.torinos.ui.article

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleLoadMoreTest {
    @Test
    fun doesNotLoadMoreBeforeItemsAreLaidOut() {
        // 1ページ目の結果で canLoadMore が true になった直後、項目がまだ配置されていない状態。
        assertFalse(shouldLoadMoreArticles(lastVisibleIndex = -1, totalItemsCount = 0, canLoadMore = true, isLoadingMore = false))
    }

    @Test
    fun loadsMoreNearTheEnd() {
        assertTrue(shouldLoadMoreArticles(lastVisibleIndex = 46, totalItemsCount = 50, canLoadMore = true, isLoadingMore = false))
        assertFalse(shouldLoadMoreArticles(lastVisibleIndex = 8, totalItemsCount = 50, canLoadMore = true, isLoadingMore = false))
    }

    @Test
    fun shortListThatFitsOnScreenLoadsMore() {
        assertTrue(shouldLoadMoreArticles(lastVisibleIndex = 2, totalItemsCount = 3, canLoadMore = true, isLoadingMore = false))
    }

    @Test
    fun doesNotLoadWhileLoadingOrWithoutMore() {
        assertFalse(shouldLoadMoreArticles(lastVisibleIndex = 49, totalItemsCount = 50, canLoadMore = false, isLoadingMore = false))
        assertFalse(shouldLoadMoreArticles(lastVisibleIndex = 49, totalItemsCount = 50, canLoadMore = true, isLoadingMore = true))
    }
}
