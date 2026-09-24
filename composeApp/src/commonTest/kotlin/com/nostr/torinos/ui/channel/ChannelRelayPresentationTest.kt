package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.ChannelRelayContext
import com.nostr.torinos.network.RelayConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelRelayPresentationTest {
    private fun context(recommended: List<String>, read: List<String>, write: List<String>) =
        ChannelRelayContext(recommended, read.toCollection(linkedSetOf()), write.toCollection(linkedSetOf()), recommended.firstOrNull())

    @Test
    fun headerShowsHostsCountOrUserRelays() {
        assertEquals("yabu.me・r.kojira.io", ChannelRelayPresentation.headerSummary(context(listOf("wss://yabu.me", "wss://r.kojira.io"), emptyList(), emptyList())))
        assertEquals("3 relays", ChannelRelayPresentation.headerSummary(context(listOf("wss://a", "wss://b", "wss://c"), emptyList(), emptyList())))
        assertEquals("ユーザーリレーを使用中", ChannelRelayPresentation.headerSummary(context(emptyList(), listOf("wss://nav"), emptyList())))
    }

    @Test
    fun composerShowsTargetsProgressAndPartialFailure() {
        val ctx = context(listOf("wss://a"), listOf("wss://a"), listOf("wss://a", "wss://b"))
        assertEquals("投稿先: 2 relays", ChannelRelayPresentation.composerSummary(ctx, ChannelPublishUiState.Idle))
        val sending = ChannelPublishUiState(ChannelPublishUiState.Phase.Sending, listOf("wss://a", "wss://b"), succeeded = setOf("wss://a"))
        assertEquals("送信中 1/2", ChannelRelayPresentation.composerSummary(ctx, sending))
        val partial = sending.copy(phase = ChannelPublishUiState.Phase.PartialSuccess, failed = mapOf("wss://b" to "timeout"))
        assertEquals("2件中1件に送信しました", ChannelRelayPresentation.composerSummary(ctx, partial))
        assertEquals("投稿先: あなたの書き込みリレー", ChannelRelayPresentation.composerSummary(ChannelRelayContext.EMPTY, ChannelPublishUiState.Idle))
    }

    @Test
    fun rowsOrderRolesConnectionAndLastPublish() {
        val ctx = context(listOf("wss://rec"), listOf("wss://rec", "wss://nav"), listOf("wss://rec", "wss://mine"))
        val publish = ChannelPublishUiState(
            ChannelPublishUiState.Phase.PartialSuccess,
            targets = listOf("wss://rec", "wss://mine"),
            succeeded = setOf("wss://rec"),
            failed = mapOf("wss://mine" to "auth-required"),
        )
        val rows = ChannelRelayPresentation.rows(ctx, mapOf("wss://rec" to RelayConnectionState.Connected), publish)
        assertEquals(listOf("wss://rec", "wss://nav", "wss://mine"), rows.map { it.url })
        val rec = rows[0]
        assertEquals(true, rec.isRecommended && rec.usedForRead && rec.usedForWrite)
        assertEquals(RelayConnectionState.Connected, rec.connection)
        assertEquals(ChannelRelayRow.LastPublish.Succeeded, rec.lastPublish)
        // 状態が未報告の閲覧先は接続待ち、投稿専用は購読しないので接続状態なし。
        assertEquals(RelayConnectionState.Connecting, rows[1].connection)
        assertNull(rows[1].lastPublish)
        assertNull(rows[2].connection)
        assertEquals(ChannelRelayRow.LastPublish.Failed("auth-required"), rows[2].lastPublish)
    }

    @Test
    fun refusalIsShownOnlyForReadRelays() {
        val ctx = context(listOf("wss://rec"), listOf("wss://rec"), listOf("wss://rec", "wss://mine"))
        val rows = ChannelRelayPresentation.rows(
            ctx,
            mapOf("wss://rec" to RelayConnectionState.Connected),
            ChannelPublishUiState.Idle,
            refusals = mapOf("wss://rec" to "auth-required: authentication required", "wss://mine" to "blocked"),
        )
        assertEquals("auth-required: authentication required", rows.first { it.url == "wss://rec" }.refusal)
        // 投稿専用のリレーは購読しないので、拒否理由も出さない。
        assertNull(rows.first { it.url == "wss://mine" }.refusal)
    }
}
