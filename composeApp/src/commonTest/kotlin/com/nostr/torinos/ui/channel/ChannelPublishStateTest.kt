package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.ChannelRelayContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayEntry
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
    fun lateSuccessIsMergedWithoutLosingTheFirstAcceptedRelay() {
        val first = ChannelPublishUiState.sending(targets)
            .withRelayResult(RelayPublishResult(setOf("wss://a"), emptyMap()))
        val completed = first.withRelayResult(RelayPublishResult(setOf("wss://b"), emptyMap()))
        assertEquals(ChannelPublishUiState.Phase.Success, completed.phase)
        assertEquals(targets.toSet(), completed.succeeded)
    }

    @Test
    fun staleIconUploadCannotUpdateAReopenedCreateDialog() {
        val ready = ChannelListViewModel.UiState.Ready(
            createDialog = ChannelListViewModel.CreateDialogState(sessionId = 2, name = "new"),
        )
        val stale = ready.withCreateDialogSession(1) { it.copy(picture = "https://old.example/icon") }
        assertEquals(ready, stale)
        val current = ready.withCreateDialogSession(2) { it.copy(picture = "https://new.example/icon") }
        assertEquals("https://new.example/icon", current.createDialog?.picture)
    }

    @Test
    fun latePartialFailureNoticeSurvivesSheetDisposalUntilConsumed() {
        val notice = ChannelCreateNoticeStore.Notice("late-event", "author", "2件中1件に送信しました")
        ChannelCreateNoticeStore.enqueue(notice)
        ChannelCreateNoticeStore.enqueue(notice)
        assertEquals(listOf(notice), ChannelCreateNoticeStore.pending.value.filter { it.eventId == "late-event" })
        ChannelCreateNoticeStore.consume("late-event")
        assertEquals(emptyList(), ChannelCreateNoticeStore.pending.value.filter { it.eventId == "late-event" })
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

    @Test
    fun createPlanSendsToRecommendedPlusUserWritesAndHintsFirstRecommended() {
        val entries = listOf(
            RelayEntry("wss://mine", enabled = true),
            RelayEntry("wss://readonly", enabled = true, write = false),
        )
        val plan = ChannelCreatePlan.from(listOf("wss://rec-a/", "wss://readonly", "wss://rec-b"), entries)
        assertEquals(listOf("wss://rec-a", "wss://readonly", "wss://rec-b"), plan.recommendedRelays)
        // write を切ったユーザー設定のリレーは content.relays には残るが、送信先からは外れる。
        assertEquals(listOf("wss://rec-a", "wss://rec-b", "wss://mine"), plan.relayUrls)
        assertEquals("wss://rec-a", plan.primaryHint)
    }

    @Test
    fun createPlanWithoutRecommendedRelaysFallsBackToUserWrites() {
        val plan = ChannelCreatePlan.from(emptyList(), listOf(RelayEntry("wss://mine", enabled = true)))
        assertEquals(emptyList(), plan.recommendedRelays)
        assertEquals(listOf("wss://mine"), plan.relayUrls)
        assertEquals("wss://mine", plan.primaryHint)
    }

    @Test
    fun editDeliveryAddsNewRecommendedRelaysOnly() {
        val entries = listOf(RelayEntry("wss://readonly", enabled = true, write = false))
        val delivery = ChannelEditDelivery.plan(
            baseRelayUrls = listOf("wss://old", "wss://mine"),
            previousRecommended = listOf("wss://old"),
            nextRecommended = listOf("wss://old", "wss://new/", "wss://readonly"),
            userWritableRelays = listOf("wss://mine"),
            relayEntries = entries,
        )
        assertEquals(listOf("wss://new"), delivery.addedRelays)
        assertEquals(listOf("wss://old", "wss://mine", "wss://new"), delivery.metadataRelayUrls)
    }

    @Test
    fun editDeliveryWithoutAdditionsKeepsTheSnapshotTargets() {
        val delivery = ChannelEditDelivery.plan(listOf("wss://a"), listOf("wss://a", "wss://b"), listOf("wss://a"), emptyList(), emptyList())
        assertEquals(emptyList(), delivery.addedRelays)
        assertEquals(listOf("wss://a"), delivery.metadataRelayUrls)
        // 送信先の snapshot が無い(ユーザーの書き込みリレーへ送る)場合も、追加があれば明示リストにする。
        val fromUserRelays = ChannelEditDelivery.plan(null, emptyList(), listOf("wss://new"), listOf("wss://mine"), emptyList())
        assertEquals(listOf("wss://mine", "wss://new"), fromUserRelays.metadataRelayUrls)
    }
}
