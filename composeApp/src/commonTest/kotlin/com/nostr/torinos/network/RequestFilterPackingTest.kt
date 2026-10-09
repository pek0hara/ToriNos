package com.nostr.torinos.network

import com.nostr.torinos.model.NostrFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestFilterPackingTest {
    @Test
    fun filtersDifferingOnlyInOneTagListAreMergedIntoOne() {
        val packed = packFilters(
            listOf(
                NostrFilter(kinds = listOf(30009), authors = listOf("p"), dTags = listOf("a")),
                NostrFilter(kinds = listOf(30009), authors = listOf("p"), dTags = listOf("b")),
            ),
        )

        assertEquals(
            listOf(listOf(NostrFilter(kinds = listOf(30009), authors = listOf("p"), dTags = listOf("a", "b")))),
            packed,
        )
    }

    @Test
    fun filtersDifferingInTwoFieldsAreNotMergedBecauseThatWouldWidenTheQuery() {
        val first = NostrFilter(kinds = listOf(30009), authors = listOf("p1"), dTags = listOf("a"))
        val second = NostrFilter(kinds = listOf(30009), authors = listOf("p2"), dTags = listOf("b"))

        assertEquals(listOf(listOf(first, second)), packFilters(listOf(first, second)))
    }

    @Test
    fun filtersWithLimitAreNotMergedBecauseTheLimitAppliesPerFilter() {
        val first = NostrFilter(kinds = listOf(8), pTags = listOf("a"), limit = 10)
        val second = NostrFilter(kinds = listOf(8), pTags = listOf("b"), limit = 10)

        assertEquals(listOf(listOf(first, second)), packFilters(listOf(first, second)))
    }

    @Test
    fun deletionLookupsMergePerTagKind() {
        val packed = packFilters(
            listOf(
                NostrFilter(kinds = listOf(5), authors = listOf("p"), eTags = listOf("e1")),
                NostrFilter(kinds = listOf(5), authors = listOf("p"), aTags = listOf("a1")),
                NostrFilter(kinds = listOf(5), authors = listOf("p"), eTags = listOf("e2")),
                NostrFilter(kinds = listOf(5), authors = listOf("p"), aTags = listOf("a2")),
            ),
        )

        assertEquals(
            listOf(
                listOf(
                    NostrFilter(kinds = listOf(5), authors = listOf("p"), eTags = listOf("e1", "e2")),
                    NostrFilter(kinds = listOf(5), authors = listOf("p"), aTags = listOf("a1", "a2")),
                ),
            ),
            packed,
        )
    }

    @Test
    fun requestsHoldAtMostTheGivenNumberOfFilters() {
        val filters = (0 until 25).map { NostrFilter(kinds = listOf(30009), authors = listOf("p$it"), dTags = listOf("d$it")) }

        val packed = packFilters(filters, maxFiltersPerRequest = 10)

        assertEquals(listOf(10, 10, 5), packed.map { it.size })
        assertEquals(filters, packed.flatten())
    }

    @Test
    fun filtersWithEmptyTagValuesAreSentInTheirOwnRequest() {
        // 空の d は NIP-01 上は正しいが、拒否するリレーがある。他のフィルターを巻き込まないよう分ける。
        val empty = NostrFilter(kinds = listOf(30009), authors = listOf("p"), dTags = listOf(""))
        val regular = NostrFilter(kinds = listOf(30009), authors = listOf("p"), dTags = listOf("a"))
        val other = NostrFilter(kinds = listOf(5), authors = listOf("p"), eTags = listOf("e"))

        val packed = packFilters(listOf(empty, regular, other))

        assertEquals(listOf(listOf(regular, other), listOf(empty)), packed)
        assertTrue(packed.none { request -> request.size > 1 && empty in request })
    }

    @Test
    fun duplicateValuesAndFiltersAreRemoved() {
        val filter = NostrFilter(kinds = listOf(8), ids = listOf("x"))

        assertEquals(listOf(listOf(filter)), packFilters(listOf(filter, filter, NostrFilter(kinds = listOf(8), ids = listOf("x")))))
    }
}
