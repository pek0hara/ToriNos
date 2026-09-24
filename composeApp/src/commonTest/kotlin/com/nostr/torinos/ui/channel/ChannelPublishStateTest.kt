package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.ChannelRelayContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.ui.timeline.SignedPublishResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelPublishStateTest {
    private val targets = listOf("wss://a", "wss://b")
    private val event = NostrEvent("id", "me", 1, 42, emptyList(), "hi", "sig")

    @Test
    fun allSuccessPartialSuccessAndAllFailureAreDistinguished() {
        val all = ChannelPublishUiState.from(
            targets,
            SignedPublishResult.Published(event, RelayPublishResult(setOf("wss://a", "wss://b"), emptyMap())),
        )
        assertEquals(ChannelPublishUiState.Phase.Success, all.phase)
        assertNull(all.summary)

        val partial = ChannelPublishUiState.from(
            targets,
            SignedPublishResult.Published(event, RelayPublishResult(setOf("wss://a"), mapOf("wss://b" to "timeout"))),
        )
        assertEquals(ChannelPublishUiState.Phase.PartialSuccess, partial.phase)
        assertEquals(mapOf("wss://b" to "timeout"), partial.failed)
        assertEquals("2件中1件に送信しました", partial.summary)

        val none = ChannelPublishUiState.from(
            targets,
            SignedPublishResult.Failed(
                IllegalStateException("x"),
                RelayPublishResult(emptySet(), mapOf("wss://a" to "auth-required", "wss://b" to "timeout")),
            ),
        )
        assertEquals(ChannelPublishUiState.Phase.Failed, none.phase)
        assertEquals("2件すべてのリレーへの送信に失敗しました", none.summary)
    }

    @Test
    fun resultsArrivingAfterFirstSuccessCompleteTheState() {
        val first = ChannelPublishUiState.from(
            targets,
            SignedPublishResult.Published(event, RelayPublishResult(setOf("wss://a"), emptyMap())),
        )
        assertEquals(ChannelPublishUiState.Phase.Sending, first.phase)
        assertEquals(setOf("wss://a"), first.succeeded)
        val done = first.withRelayResult(RelayPublishResult(emptySet(), mapOf("wss://b" to "auth-required")))
        assertEquals(ChannelPublishUiState.Phase.PartialSuccess, done.phase)
        assertEquals("2件中1件に送信しました", done.summary)
        // 同じ結果が重ねて届いても変わらない。
        assertEquals(done, done.withRelayResult(RelayPublishResult(setOf("wss://a"), emptyMap())))
    }

    @Test
    fun publishingToUserRelaysWithoutTargetsIsImmediateSuccess() {
        val state = ChannelPublishUiState.from(emptyList(), SignedPublishResult.Published(event))
        assertEquals(ChannelPublishUiState.Phase.Success, state.phase)
    }

    @Test
    fun snapshotUsesWriteRelaysAndPrimaryHintAndFallsBackWhenEmpty() {
        val context = ChannelRelayContext(
            recommendedRelays = listOf("wss://rec"),
            readRelays = linkedSetOf("wss://rec", "wss://nav"),
            writeRelays = linkedSetOf("wss://rec", "wss://mine"),
            primaryHint = "wss://rec",
        )
        assertEquals(ChannelPublishContext(listOf("wss://rec", "wss://mine"), "wss://rec"), ChannelPublishContext.from(context))
        assertEquals(ChannelPublishContext(null, null), ChannelPublishContext.from(ChannelRelayContext.EMPTY))
    }
}
