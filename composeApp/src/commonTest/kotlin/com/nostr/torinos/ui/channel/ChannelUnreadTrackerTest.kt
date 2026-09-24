package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelUnreadTrackerTest {
    @Test
    fun planGroupsNearbyReadPositionsIntoChunksWithTheirOwnSince() {
        val chunks = ChannelUnreadCatchUp.plan(
            mapOf("a" to 100L, "b" to 10L, "c" to 90L, "d" to 5L),
            chunkSize = 2,
        )
        assertEquals(listOf(listOf("a", "c"), listOf("b", "d")), chunks.map { it.channelIds })
        assertEquals(listOf(90L, 5L), chunks.map { it.since })
    }

    @Test
    fun tallyCountsOnlyMessagesNewerThanEachChannelsReadPosition() {
        val chunk = ChannelUnreadCatchUpChunk(listOf("a", "b"), since = 10)
        val results = ChannelUnreadCatchUp.tally(
            chunk,
            listOf(msg("1", "a", 50), msg("2", "a", 60), msg("2", "a", 60), msg("3", "b", 15), msg("4", "b", 30), msg("5", "z", 99)),
            lastReadAts = mapOf("a" to 40L, "b" to 20L),
            limit = 100,
            channelIdOf = ::channelOf,
        )
        assertEquals(2, results.getValue("a").count)
        assertEquals(1, results.getValue("b").count)
        assertFalse(results.getValue("a").isLowerBound)
    }

    @Test
    fun saturatedChunkMarksEveryChannelAsLowerBound() {
        val chunk = ChannelUnreadCatchUpChunk(listOf("a", "b"), since = 0)
        val results = ChannelUnreadCatchUp.tally(
            chunk,
            listOf(msg("1", "a", 5), msg("2", "a", 6)),
            lastReadAts = mapOf("a" to 0L, "b" to 0L),
            limit = 2,
            channelIdOf = ::channelOf,
        )
        assertTrue(results.getValue("a").isLowerBound)
        assertTrue(results.getValue("b").isLowerBound)
        assertEquals(0, results.getValue("b").count)
    }

    @Test
    fun saturatedChunkSplitsIntoHalvesWithTheirOwnSince() {
        val lastReadAts = mapOf("a" to 100L, "b" to 90L, "c" to 10L)
        val parts = ChannelUnreadCatchUp.split(ChannelUnreadCatchUpChunk(listOf("a", "b", "c"), 10), lastReadAts)
        assertEquals(listOf(listOf("a", "b"), listOf("c")), parts.map { it.channelIds })
        assertEquals(listOf(90L, 10L), parts.map { it.since })
        assertEquals(emptyList(), ChannelUnreadCatchUp.split(parts[1], lastReadAts))
    }

    @Test
    fun liveMessagesAddToCatchUpAndDuplicatesCountOnce() {
        val tracker = ChannelUnreadTracker()
        tracker.applyCatchUp(mapOf("a" to ChannelUnreadCatchUpResult(3, false, 40)), mapOf("a" to 40L))
        assertTrue(tracker.onLive("a", "x", 100))
        assertFalse(tracker.onLive("a", "x", 100))
        tracker.onLive("a", "old", 30) // 既読位置より古いライブ受信は数えない
        assertEquals(ChannelUnreadBadge(count = 4), tracker.badge("a", 40))
    }

    @Test
    fun readingDuringCatchUpDiscardsStaleResult() {
        val tracker = ChannelUnreadTracker()
        tracker.applyCatchUp(mapOf("a" to ChannelUnreadCatchUpResult(5, false, 40)), mapOf("a" to 90L))
        assertEquals(0, tracker.badge("a", 90).count)
    }

    @Test
    fun markingReadResetsCountButKeepsLaterLiveMessages() {
        val tracker = ChannelUnreadTracker()
        tracker.applyCatchUp(mapOf("a" to ChannelUnreadCatchUpResult(5, true, 40)), mapOf("a" to 40L))
        tracker.onLive("a", "x", 100)
        tracker.onLive("a", "y", 120)
        assertEquals(ChannelUnreadBadge(count = 7, isLowerBound = true), tracker.badge("a", 40))
        assertEquals(ChannelUnreadBadge(count = 1), tracker.badge("a", 100))
    }

    @Test
    fun unopenedChannelShowsOnlyNewActivityFlag() {
        val tracker = ChannelUnreadTracker()
        assertEquals(ChannelUnreadBadge(), tracker.badge("a", null))
        tracker.onLive("a", "x", 100)
        assertEquals(ChannelUnreadBadge(hasNewActivity = true), tracker.badge("a", null))
    }

    @Test
    fun liveBufferOverflowIsReportedAsLowerBound() {
        val tracker = ChannelUnreadTracker(maxLiveEventsPerChannel = 2)
        tracker.onLive("a", "1", 10)
        tracker.onLive("a", "2", 11)
        tracker.onLive("a", "3", 12)
        assertEquals(ChannelUnreadBadge(count = 2, isLowerBound = true), tracker.badge("a", 0))
    }

    @Test
    fun effectiveLimitNeverExceedsRelayCapSoSaturationIsDetectable() {
        assertEquals(500, ChannelUnreadCatchUp.effectiveLimit(null))
        assertEquals(300, ChannelUnreadCatchUp.effectiveLimit(300))
        assertEquals(1_000, ChannelUnreadCatchUp.effectiveLimit(5_000))
        assertEquals(500, ChannelUnreadCatchUp.effectiveLimit(0))
    }

    @Test
    fun badgeTextCapsAndMarksLowerBound() {
        assertEquals("3", unreadBadgeText(3, false))
        assertEquals("3+", unreadBadgeText(3, true))
        assertEquals("99+", unreadBadgeText(150, false))
    }

    private fun msg(id: String, channelId: String, createdAt: Long) =
        NostrEvent(id, "p", createdAt, 42, listOf(listOf("e", channelId, "", "root")), "", "sig")

    private fun channelOf(event: NostrEvent): String? = event.tags.firstOrNull()?.getOrNull(1)
}
