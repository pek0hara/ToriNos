package com.nostr.torinos.ui.channel

import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelRelayPlannerTest {
    private val entries = listOf(
        RelayEntry("wss://user-rw", enabled = true),
        RelayEntry("wss://user-readonly", enabled = true, read = true, write = false),
        RelayEntry("wss://disabled", enabled = false),
    )

    @Test
    fun recommendedRelaysComeFirstAndNavigationHintIsTheBootstrap() {
        val context = ChannelRelayPlanner.context(listOf("wss://rec-a/", "wss://rec-b"), "wss://nav", entries)
        assertEquals(listOf("wss://rec-a", "wss://rec-b", "wss://nav"), context.readRelays.toList())
        assertEquals(listOf("wss://rec-a", "wss://rec-b", "wss://user-rw"), context.writeRelays.toList())
        assertEquals("wss://rec-a", context.primaryHint)
    }

    @Test
    fun userReadRelaysAreTheBootstrapWithoutNavigationHint() {
        val context = ChannelRelayPlanner.context(emptyList(), null, entries)
        assertEquals(setOf("wss://user-rw", "wss://user-readonly"), context.readRelays)
        assertEquals(setOf("wss://user-rw"), context.writeRelays)
    }

    @Test
    fun readOnlyUserSettingExcludesRecommendedRelayFromWritesButNotFromReads() {
        val context = ChannelRelayPlanner.context(listOf("wss://user-readonly"), "wss://nav", entries)
        assertTrue("wss://user-readonly" in context.readRelays)
        assertFalse("wss://user-readonly" in context.writeRelays)
        assertEquals("wss://user-readonly", context.primaryHint)
    }

    @Test
    fun readTargetFallsBackOnlyWhenNothingIsKnown() {
        assertEquals(RelayTarget.Explicit(setOf("wss://a")), ChannelRelayPlanner.readTarget(setOf("wss://a"), "wss://nav"))
        assertEquals(RelayTarget.Single("wss://nav"), ChannelRelayPlanner.readTarget(emptySet(), "wss://nav"))
        assertEquals(RelayTarget.AllEnabled, ChannelRelayPlanner.readTarget(emptySet(), null))
    }

    @Test
    fun transitionSubscribesUnionThenShrinksToNext() {
        val old = ChannelRelayPlanner.context(listOf("wss://a", "wss://b"), "wss://nav", entries)
        val next = ChannelRelayPlanner.context(listOf("wss://b", "wss://c"), "wss://nav", entries)
        val transition = ChannelRelayPlanner.transition(old, next)
        assertEquals(setOf("wss://a", "wss://b", "wss://c", "wss://nav"), transition.transitionTargets)
        assertEquals(setOf("wss://c"), transition.addedRelays)
        assertEquals(setOf("wss://b", "wss://c", "wss://nav"), transition.finalTargets)
        assertTrue(transition.needsWarmUp)
    }

    @Test
    fun removingRelaysOnlyNeedsNoWarmUp() {
        val old = ChannelRelayPlanner.context(listOf("wss://a", "wss://b"), "wss://nav", entries)
        val next = ChannelRelayPlanner.context(listOf("wss://b"), "wss://nav", entries)
        assertFalse(ChannelRelayPlanner.transition(old, next).needsWarmUp)
    }
}
