package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FR-05: RelayTarget.Explicitは未登録の推奨リレーも接続対象にできる必要がある(第16.6節)。
 * urls()はNostrRepositoryの購読・接続ライフサイクル全体が経由する唯一の解決点。
 */
class RelayTargetUrlsTest {
    private val enabled = listOf("wss://a.example", "wss://b.example")

    @Test
    fun allEnabledReturnsEnabledRelaysAsIs() {
        assertEquals(enabled, RelayTarget.AllEnabled.urls(enabled))
    }

    @Test
    fun singleIsRestrictedToEnabledRelays() {
        assertEquals(listOf("wss://a.example"), RelayTarget.Single("wss://a.example").urls(enabled))
        assertEquals(emptyList(), RelayTarget.Single("wss://unregistered.example").urls(enabled))
    }

    @Test
    fun explicitIncludesUrlsNotInEnabledRelays() {
        val target = RelayTarget.Explicit(setOf("wss://unregistered.example", "wss://a.example"))
        assertEquals(setOf("wss://unregistered.example", "wss://a.example"), target.urls(enabled).toSet())
    }

    @Test
    fun explicitNormalizesAndDeduplicates() {
        val target = RelayTarget.Explicit(setOf("WSS://Relay.Example/", "wss://relay.example", "https://invalid.example"))
        assertEquals(listOf("wss://relay.example"), target.urls(enabled))
    }
}
