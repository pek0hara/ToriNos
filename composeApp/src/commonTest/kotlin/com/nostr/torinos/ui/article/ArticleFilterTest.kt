package com.nostr.torinos.ui.article

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleFilterTest {
    @Test
    fun toAuthorScope_mapsEachFilterWhenLoggedIn() {
        assertEquals(ArticleAuthorScope.All, ArticleAuthorFilter.All.toAuthorScope("me"))
        assertEquals(ArticleAuthorScope.Following, ArticleAuthorFilter.Following.toAuthorScope("me"))
        assertEquals(ArticleAuthorScope.Only("me"), ArticleAuthorFilter.Own.toAuthorScope("me"))
    }

    @Test
    fun toAuthorScope_fallsBackToAllWhenLoggedOut() {
        ArticleAuthorFilter.entries.forEach { filter ->
            assertEquals(ArticleAuthorScope.All, filter.toAuthorScope(null))
        }
    }

    @Test
    fun floatingFiltersHeight_reservesSpaceOnlyForVisibleFilters() {
        assertEquals(0.dp, articleFloatingFiltersHeight(hasAuthorFilter = false, hasTopic = false))
        assertEquals(48.dp, articleFloatingFiltersHeight(hasAuthorFilter = true, hasTopic = false))
        assertEquals(40.dp, articleFloatingFiltersHeight(hasAuthorFilter = false, hasTopic = true))
        assertEquals(86.dp, articleFloatingFiltersHeight(hasAuthorFilter = true, hasTopic = true))
    }
}
