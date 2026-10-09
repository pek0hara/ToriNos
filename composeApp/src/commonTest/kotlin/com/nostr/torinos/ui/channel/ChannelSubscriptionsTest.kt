package com.nostr.torinos.ui.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChannelSubscriptionsTest {
    private val channelId = "72db026181d17cc6".repeat(4)

    @Test
    fun subscriptionIdsFitRelayLimits() {
        val ids = ChannelSubscriptionIds(channelId, sessionKey = "-1612887794", relayKey = "-2047878520")
        val all = listOf(ids.meta, ids.messages, ids.history + "-123", ids.engagement, ids.ownHides)
        all.forEach { assertTrue(it.length <= 50, it) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun differentSessionsOrRelaysGetDifferentIds() {
        val a = ChannelSubscriptionIds(channelId, sessionKey = "1", relayKey = "all")
        val b = ChannelSubscriptionIds(channelId, sessionKey = "2", relayKey = "all")
        val c = ChannelSubscriptionIds(channelId, sessionKey = "1", relayKey = "x")
        assertNotEquals(a.meta, b.meta)
        assertNotEquals(a.meta, c.meta)
    }

    @Test
    fun metaFiltersAskForCreateAndUpdatesTogether() {
        val filters = channelMetaFilters(channelId)
        assertEquals(listOf(channelId), filters[0].ids)
        assertEquals(listOf(41), filters[1].kinds)
        assertEquals(listOf(channelId), filters[1].eTags)
    }

    @Test
    fun engagementFiltersCoverRepliesReactionsRepostsAndQuotes() {
        val filters = channelEngagementFilters(listOf("m1", "m2"), messageKind = 42)
        assertEquals(listOf(listOf(42), listOf(7), listOf(6, 16), listOf(42)), filters.map { it.kinds })
        assertEquals(listOf("m1", "m2"), filters[0].eTags)
        assertEquals(listOf("m1", "m2"), filters[3].qTags)
        assertTrue(filters.all { it.limit == 500 })
    }

    @Test
    fun ownHideFiltersAskForHidesAndTheirDeletions() {
        val filters = channelOwnHideFilters("me")
        assertEquals(listOf(listOf(43), listOf(5)), filters.map { it.kinds })
        assertTrue(filters.all { it.authors == listOf("me") })
        assertEquals(listOf("43"), filters[1].parentKindTags)
    }
}
