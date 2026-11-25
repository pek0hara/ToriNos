package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OwnRecentPostsTest {
    private val own = "1".repeat(64)
    private val other = "2".repeat(64)

    private fun post(id: Char, createdAt: Long, pubkey: String = own, kind: Int = 1) =
        NostrEvent(id.toString().repeat(64), pubkey, createdAt, kind, emptyList(), "", "0".repeat(128))

    @Test fun keepsOnlyOwnKind1NewestFirstWithinLimit() {
        val current = listOf(OwnPostRef("a".repeat(64), 10))
        val merged = mergeOwnRecentPosts(
            current,
            listOf(post('b', 30), post('c', 20, pubkey = other), post('d', 5), post('e', 40, kind = 7), post('a', 10)),
            own, limit = 2,
        )
        assertEquals(listOf(OwnPostRef("b".repeat(64), 30), OwnPostRef("a".repeat(64), 10)), merged)
    }

    @Test fun quoteFilterIsAbsentWithoutOwnPosts() {
        assertNull(quoteFilter(emptyList(), 100))
        assertEquals(NostrFilter(kinds = listOf(1), qTags = listOf("a".repeat(64)), limit = 100), quoteFilter(listOf("a".repeat(64)), 100))
    }
}
