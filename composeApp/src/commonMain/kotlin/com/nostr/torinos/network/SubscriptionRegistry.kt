package com.nostr.torinos.network

import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.buildReqMessage
import com.nostr.torinos.model.buildCloseMessage
import com.nostr.torinos.util.fetchTraceLog

/** RepositoryのstateMutex配下でレコードと送信命令を管理する。I/Oは行わない。 */
internal class SubscriptionRegistry {
    val records = mutableMapOf<String, ActiveSubscriptionRecord>()
    val slotsFreedRelays = linkedSetOf<String>()
    val openOrder = RelayOpenOrder()

    fun register(id: String, record: ActiveSubscriptionRecord) {
        check(id !in records) { "購読IDはすでに使用されています: $id" }
        records[id] = record
    }

    fun owns(session: SubscriptionSessionImpl): Boolean = records[session.id]?.session === session

    fun reconcile(
        subscriptionId: String,
        record: ActiveSubscriptionRecord,
        enabledRelayUrls: List<String>,
        relayConnectionGenerations: Map<String, Long>,
        subscriptionLimit: (String) -> Int,
        restartFetchTimeout: (String, SubscriptionSessionImpl, SubscriptionBehavior.Fetch) -> Unit,
        onlyRelayUrl: String? = null,
    ): List<SubscriptionWireCommand> {
        val desiredRelayUrls = record.target.urls(enabledRelayUrls).toSet()
        record.session?.configureSpec(SubscriptionSpec(subscriptionId, record.filters, record.target, record.behavior), desiredRelayUrls)
        val relayUrls = (record.relayStates.keys + desiredRelayUrls)
            .let { urls -> if (onlyRelayUrl == null) urls else urls.filter { it == onlyRelayUrl } }

        return relayUrls.mapNotNull { relayUrl ->
            val desiredFilters = record.filters.takeIf { relayUrl in desiredRelayUrls }
            val generation = relayConnectionGenerations[relayUrl] ?: 0L
            val previous = record.relayStates[relayUrl]
            val wasOccupying = RelaySubscriptionSlots.occupies(previous)
            val result = SubscriptionStateMachine.reconcile(
                state = previous,
                desiredFilters = desiredFilters,
                connectionGeneration = generation,
                hasFreeSlot = records.values.count { RelaySubscriptionSlots.occupies(it.relayStates[relayUrl]) } < subscriptionLimit(relayUrl),
            )
            if (result.state == null) {
                record.relayStates.remove(relayUrl)
            } else {
                record.relayStates[relayUrl] = result.state
            }
            val occupies = RelaySubscriptionSlots.occupies(result.state)
            if (wasOccupying && !occupies) slotsFreedRelays += relayUrl
            if (!wasOccupying && occupies) {
                openOrder.opened(relayUrl, subscriptionId)
                // 空きを待っていた有限取得は、実際に送った時点から待ち時間を数え直す。
                // 待っていた間にタイムアウトすると、リレーへ聞く前に取得失敗になる。
                val behavior = record.behavior
                if (previous != null && behavior is SubscriptionBehavior.Fetch) {
                    record.session?.let { restartFetchTimeout(subscriptionId, it, behavior) }
                }
            }
            if (previous == null && desiredFilters != null && result.command == null && !occupies) {
                fetchTraceLog { "WAIT $subscriptionId → $relayUrl 同時購読数の上限のため空きを待つ" }
            }
            when (val command = result.command) {
                is SubscriptionCommandDecision.Open -> SubscriptionWireCommand(
                    relayUrl = relayUrl,
                    message = buildReqMessage(subscriptionId, command.filters),
                    generation = generation,
                    subscriptionId = subscriptionId,
                    filters = command.filters,
                )
                SubscriptionCommandDecision.Close -> SubscriptionWireCommand(
                    relayUrl = relayUrl,
                    message = buildCloseMessage(subscriptionId),
                    generation = generation,
                    subscriptionId = subscriptionId,
                )
                null -> null
            }
        }
    }

}

internal data class ActiveSubscriptionRecord(
    var filters: List<NostrFilter>,
    var target: RelayTarget,
    val behavior: SubscriptionBehavior,
    val session: SubscriptionSessionImpl? = null,
    val relayStates: MutableMap<String, RelaySubscriptionState> = mutableMapOf(),
    val seenEventIds: BoundedIdSet = BoundedIdSet(4_096),
    val deduplicateEvents: Boolean = true,
    val replayCachedEvents: Boolean = true,
    val fetchExpectedRelays: Set<String>? = null,
    val fetchOutcomes: MutableMap<String, RelayOutcome> = mutableMapOf(),
    var fetchCompletionScheduled: Boolean = false,
    var fetchCompleted: Boolean = false,
    /** キャッシュを流し終えるまでは、接続できないリレーで取得を終えない（[NostrRepository.openSubscription]）。 */
    var unavailableSweepEnabled: Boolean = false,
) {
    fun shouldCompleteFetchLocked(): Boolean {
        val expected = fetchExpectedRelays ?: return false
        return !fetchCompleted && !fetchCompletionScheduled && expected.all { it in fetchOutcomes }
    }
}

internal class BoundedIdSet(
    private val capacity: Int,
) {
    private val ids = LinkedHashSet<String>()

    fun add(id: String): Boolean {
        if (!ids.add(id)) return false
        while (ids.size > capacity) {
            ids.remove(ids.first())
        }
        return true
    }
}

internal data class SubscriptionWireCommand(
    val relayUrl: String,
    val message: String,
    /** 送ると決めた時点の接続世代。リレーの現在の接続と違えば送らない。 */
    val generation: Long,
    /** 通信ログ用。REQ ならフィルター、CLOSE なら null。 */
    val subscriptionId: String? = null,
    val filters: List<NostrFilter>? = null,
)

