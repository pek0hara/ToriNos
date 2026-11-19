package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * 受賞者一覧は詳細を開いた人が待っているので、裏で進むバッジの確認（削除・リレー一覧）の
 * 順番待ちや、遅いリレーの時間切れを待たずに、届いた授与から表示する。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BadgeAwardeesFetchTest {
    private val relays = setOf("wss://one.example")
    private val award = badgeAward()

    private fun hanging(spec: SubscriptionSpec): SubscriptionSession = object : SubscriptionSession {
        override val id = spec.id
        override val signals = flow<SubscriptionSignal> { awaitCancellation() }
        override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = Unit
        override suspend fun close() = Unit
    }

    /** [slow] の kind を含む問い合わせは答えが来ないまま時間切れになる。 */
    private fun TestScope.repository(slow: Set<Int>) = BadgeRepository(backgroundScope, BadgeTransport(open = { spec ->
        if (spec.filters.any { filter -> filter.kinds.orEmpty().any { it in slow } }) hanging(spec) else badgeResponse(spec, listOf(award))
    }, validate = { true }, validationDispatcher = StandardTestDispatcher(testScheduler)), { relays }, nowMillis = { testScheduler.currentTime })

    @Test fun awardeesAppearBeforeSlowRelayListAndDeletionChecks() = runTest {
        val repo = repository(slow = setOf(5, 10002))
        val progress = mutableListOf<BadgeAwardeesResult>()
        val fetch = backgroundScope.async { repo.awardees(badgeAddress, onProgress = { progress += it }) }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(badgeOwner), progress.lastOrNull()?.awardees?.map { it.pubkey })
        assertFalse(progress.last().complete)
        assertFalse(fetch.isCompleted)
        advanceTimeBy(60_000); runCurrent()
        val result = fetch.await()
        assertEquals(listOf(badgeOwner), result.awardees.map { it.pubkey })
        assertFalse(result.complete)
        repo.close()
    }

    @Test fun awardeesDoNotQueueBehindBackgroundConfirmations() = runTest {
        val repo = repository(slow = setOf(5, 10002))
        // 裏の確認が順番待ちの枠を使い切っている状態。
        repeat(4) { n -> backgroundScope.async { repo.resolveItems(listOf(badgePair("${n + 1}".repeat(64))), relays) } }
        runCurrent()
        val progress = mutableListOf<BadgeAwardeesResult>()
        val fetch = backgroundScope.async { repo.awardees(badgeAddress, force = true, onProgress = { progress += it }) }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(badgeOwner), progress.lastOrNull()?.awardees?.map { it.pubkey })
        // 削除の確認も裏の順番待ちに並ばず、自分の時間切れ（12秒）で終わる。
        advanceTimeBy(13_000); runCurrent()
        assertTrue(fetch.isCompleted)
        repo.close()
    }

    @Test fun firstQueryEndingIsReportedWhileDeletionCheckContinues() = runTest {
        val repo = repository(slow = setOf(5))
        val progress = mutableListOf<BadgeAwardeesResult>()
        val fetch = backgroundScope.async { repo.awardees(badgeAddress, onProgress = { progress += it }) }
        advanceTimeBy(1_000); runCurrent()
        assertFalse(fetch.isCompleted)
        val last = progress.last()
        assertTrue(last.checking)
        assertEquals(listOf(badgeOwner), last.awardees.map { it.pubkey })
        assertTrue(progress.dropLast(1).none { it.checking })
        advanceTimeBy(60_000); runCurrent()
        assertFalse(fetch.await().checking)
        repo.close()
    }
}
