package com.nostr.torinos.network

import com.nostr.torinos.createHttpClient
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.RelayMessage
import com.nostr.torinos.model.buildCloseMessage
import com.nostr.torinos.model.buildEventMessage
import com.nostr.torinos.model.buildReqMessage
import com.nostr.torinos.util.appLog
import com.nostr.torinos.util.loggingExceptionHandler
import com.nostr.torinos.util.fetchTraceLog
import com.nostr.torinos.util.networkTraceLog
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RelayTarget {
    data object AllEnabled : RelayTarget
    data class Single(val url: String) : RelayTarget
    data class Explicit(val urls: Set<String>) : RelayTarget
}

data class RelayPublishResult(
    val succeededRelays: Set<String>,
    val failedRelays: Map<String, String>,
    val rejectedRelays: Set<String> = emptySet(),
) {
    val successCount: Int get() = succeededRelays.size
    val failureCount: Int get() = failedRelays.size
    val totalCount: Int get() = successCount + failureCount
}

internal class RelayRejectedException(message: String) : IllegalStateException(message)

/** OK false の応答。rate-limited は時間をおけば受理されうるので、拒否ではなく一時的な失敗として扱う。 */
private fun relayRejection(message: String): IllegalStateException {
    val reason = message.ifBlank { "リレーに拒否されました" }
    return if (reason.startsWith("rate-limited:")) IllegalStateException(reason) else RelayRejectedException(reason)
}

/**
 * - [RelayTarget.AllEnabled]: ユーザー設定のread有効リレー。
 * - [RelayTarget.Single]: 一覧UIで選択された設定済みリレーのみ(未登録なら除外)。
 * - [RelayTarget.Explicit]: 呼び出し側が明示した集合そのもの。enabledRelayUrlsに存在しない
 *   URL(チャンネル推奨リレーなど)も対象にでき、正規化のみ行う。設定済みリレーだけに限定したい
 *   呼び出し側は、渡す前に自分でenabledRelayUrlsとの積集合を取ること。
 */
internal fun RelayTarget.urls(enabledRelayUrls: List<String>): List<String> = when (this) {
    RelayTarget.AllEnabled -> enabledRelayUrls
    is RelayTarget.Single -> listOf(url).filter { it in enabledRelayUrls }
    is RelayTarget.Explicit -> normalizeRelayUrls(urls, limit = Int.MAX_VALUE)
}

object NostrRepository {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            loggingExceptionHandler("NostrRepository", "Uncaught coroutine exception"),
    )

    /** 全リレーからのメッセージを集約するバス */
    private val bus = MutableSharedFlow<RelayEnvelope>(extraBufferCapacity = 512)

    internal val httpClient = createHttpClient()
    private val eventRelayHints = EventRelayHintStore()
    private val stateMutex = Mutex()
    private val activeRelays = mutableMapOf<String, ActiveRelayHandle>()
    private var enabledRelayUrls: List<String> = readableRelayUrls(RelayStore.defaults)
    private val _routingRelayUrls = MutableStateFlow(enabledRelayUrls.toSet())
    /** Repository の購読ルーティングへ反映済みの読み込み対象リレー。 */
    internal val routingRelayUrls: StateFlow<Set<String>> = _routingRelayUrls.asStateFlow()
    private val activeSubscriptions = mutableMapOf<String, ActiveSubscriptionRecord>()
    private val relayConnectionGenerations = mutableMapOf<String, Long>()
    /** リレーから最後にメッセージを受け取った時刻（epoch 秒）。切断で途切れた配信の起点に使う。 */
    private val relayLastMessageAt = mutableMapOf<String, Long>()
    private val temporaryRelays = mutableMapOf<String, TemporaryRelayHandle>()
    private val temporarySubscriptions = mutableMapOf<String, Pair<NostrFilter, String>>()
    /** 一時接続ごとに、再同期を済ませた接続世代。 */
    private val temporaryRelayGenerations = mutableMapOf<String, Long>()
    /** リレー情報（NIP-11）の max_subscriptions。分からないリレーは既定値を使う。 */
    private val advertisedSubscriptionLimits = mutableMapOf<String, Int>()
    /** 同時購読数の超過を知らされて下げた上限。 */
    private val loweredSubscriptionLimits = mutableMapOf<String, Int>()
    /** 購読が閉じて空きができたリレー。待っている購読をまとめて開く。 */
    private val slotsFreedRelays = linkedSetOf<String>()
    /** リレーごとに最後に新しく開いた購読。購読IDのない拒否の通知を受けたとき、その購読とみなす。 */
    private val lastOpenedSubscriptions = mutableMapOf<String, String>()
    private val activeRelayCount = MutableStateFlow(0)
    private val _relayConnectionStates = MutableStateFlow<Map<String, RelayConnectionState>>(emptyMap())
    val relayConnectionStates: StateFlow<Map<String, RelayConnectionState>> =
        _relayConnectionStates.asStateFlow()

    init {
        scope.launch {
            try {
                RelayStore.relays.collect { urls ->
                    val (commands, removedHandles, newHandles) = stateMutex.withLock {
                        enabledRelayUrls = urls
                        val commands = activeSubscriptions.flatMap { (subId, record) ->
                            reconcileSubscriptionLocked(subId, record)
                        } + drainFreedSlotsLocked()
                        val (removed, added) = reconcileActiveRelaysLocked()
                        Triple(commands, removed, added)
                    }
                    // Feed などの購読側には、enabledRelayUrls と同じスナップショットだけを公開する。
                    // RelayStore を直接監視すると、こちらの反映より先に通知される競合が起きる。
                    _routingRelayUrls.value = urls.toSet()
                    newHandles.forEach { it.relay.connect(scope) }
                    sendSubscriptionCommands(commands, removedHandles)
                    closeRemovedRelayHandles(removedHandles)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] init collect error: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun createActiveRelayHandle(relay: NostrRelay): ActiveRelayHandle {
        // 同時購読数の上限をリレー情報から得る。届くまでは控えめな既定値で開く。
        scope.launch {
            val limitation = RelayInformationRepository.fetch(relay.url).getOrNull()?.limitation
            RelayLimits.recordMaxLimit(relay.url, limitation?.maxLimit)
            val limit = limitation?.maxSubscriptions ?: return@launch
            val commands = stateMutex.withLock {
                advertisedSubscriptionLimits[relay.url] = limit
                slotsFreedRelays += relay.url
                drainFreedSlotsLocked()
            }
            fetchTraceLog { "LIMIT ${relay.url} 同時購読数の上限 $limit（リレー情報）" }
            sendSubscriptionCommands(commands)
        }
        // connect() より先に購読を張り、接続直後の EVENT/EOSE を取り逃がさない。
        val messageJob = scope.launch {
            try {
                relay.messages.collect { message ->
                    when (message) {
                        is RelayMessage.Closed -> networkTraceLog {
                            "[Repo] CLOSED from ${relay.url} subId=${message.subscriptionId} reason=${message.message}"
                        }
                        else -> networkTraceLog { "[Repo] message from ${relay.url}: $message" }
                    }
                    handleActiveRelayMessage(relay.url, message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] messages collector error for ${relay.url}: ${e::class.simpleName}: ${e.message}")
            }
        }
        // connect() より先に購読を張り、接続完了通知の取り逃がしも防ぐ。
        val connectedJob = scope.launch {
            try {
                relay.connected.collect { generation ->
                    val commands = stateMutex.withLock {
                        // この接続より前に決めた REQ/CLOSE は、リレー側で世代が合わずに捨てられている。
                        // 全購読を未送信に戻し、この接続で1回だけ送る。
                        relayConnectionGenerations[relay.url] = generation
                        // 切断の瞬間は検知が遅れうるため、最後に受信した時刻を控えめな起点にする。
                        val disconnectedAt = relayLastMessageAt[relay.url] ?: nowEpochSeconds()
                        activeSubscriptions.flatMap { (subId, record) ->
                            record.relayStates[relay.url]?.let { state ->
                                record.relayStates[relay.url] =
                                    SubscriptionStateMachine.onDisconnected(state, generation, disconnectedAt)
                            }
                            reconcileSubscriptionLocked(subId, record, onlyRelayUrl = relay.url)
                        }
                    }
                    networkTraceLog { "[Repo] relay connected: ${relay.url} syncing ${commands.size} subscriptions" }
                    FetchTrace.connected(relay.url, commands.size)
                    sendSubscriptionCommands(commands)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] connected collector error for ${relay.url}: ${e::class.simpleName}: ${e.message}")
            }
        }
        val connectionStateJob = scope.launch {
            relay.connectionState.collect { connectionState ->
                stateMutex.withLock {
                    if (activeRelays[relay.url]?.relay === relay) {
                        _relayConnectionStates.update { states ->
                            states + (relay.url to connectionState)
                        }
                    }
                }
            }
        }
        return ActiveRelayHandle(relay, listOf(messageJob, connectedJob, connectionStateJob))
    }

    private fun desiredActiveRelayUrlsLocked(): Set<String> =
        activeSubscriptions.values
            .flatMap { record -> record.target.urls(enabledRelayUrls) }
            .toSet()

    private fun reconcileActiveRelaysLocked(): Pair<List<ActiveRelayHandle>, List<ActiveRelayHandle>> {
        val desiredUrls = desiredActiveRelayUrlsLocked()
        val removed = (activeRelays.keys - desiredUrls)
            .mapNotNull { url ->
                relayLastMessageAt.remove(url)
                activeRelays.remove(url)
            }
        val added = (desiredUrls - activeRelays.keys).map { url ->
            networkTraceLog { "[Repo] connecting to relay: $url" }
            val relay = NostrRelay(url, httpClient)
            createActiveRelayHandle(relay).also { activeRelays[url] = it }
        }
        activeRelayCount.value = activeRelays.size
        _relayConnectionStates.update { states -> states.filterKeys { it in desiredUrls } }
        return removed to added
    }

    private fun reconcileSubscriptionLocked(
        subscriptionId: String,
        record: ActiveSubscriptionRecord,
        onlyRelayUrl: String? = null,
    ): List<SubscriptionWireCommand> {
        val desiredRelayUrls = record.target.urls(enabledRelayUrls).toSet()
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
                hasFreeSlot = occupiedSlotsLocked(relayUrl) < subscriptionLimitLocked(relayUrl),
            )
            if (result.state == null) {
                record.relayStates.remove(relayUrl)
            } else {
                record.relayStates[relayUrl] = result.state
            }
            val occupies = RelaySubscriptionSlots.occupies(result.state)
            if (wasOccupying && !occupies) slotsFreedRelays += relayUrl
            if (!wasOccupying && occupies) {
                lastOpenedSubscriptions[relayUrl] = subscriptionId
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

    private fun restartFetchTimeout(
        subscriptionId: String,
        session: SubscriptionSessionImpl,
        behavior: SubscriptionBehavior.Fetch,
    ) {
        session.timeoutJob?.cancel()
        session.timeoutJob = scope.launch {
            delay(behavior.timeoutMillis)
            completeFetch(subscriptionId, timedOut = true)
        }
    }

    private fun occupiedSlotsLocked(relayUrl: String): Int =
        activeSubscriptions.values.count { RelaySubscriptionSlots.occupies(it.relayStates[relayUrl]) }

    private fun subscriptionLimitLocked(relayUrl: String): Int = RelaySubscriptionSlots.limit(
        advertised = advertisedSubscriptionLimits[relayUrl],
        lowered = loweredSubscriptionLimits[relayUrl],
    )

    /** 空きができたリレーで、待っている購読を作成順に開く。 */
    private fun drainFreedSlotsLocked(): List<SubscriptionWireCommand> {
        val commands = mutableListOf<SubscriptionWireCommand>()
        while (slotsFreedRelays.isNotEmpty()) {
            val relayUrl = slotsFreedRelays.first()
            slotsFreedRelays.remove(relayUrl)
            activeSubscriptions.forEach { (subId, record) ->
                if (occupiedSlotsLocked(relayUrl) >= subscriptionLimitLocked(relayUrl)) return@forEach
                if (relayUrl !in record.target.urls(enabledRelayUrls)) return@forEach
                if (RelaySubscriptionSlots.occupies(record.relayStates[relayUrl])) return@forEach
                commands += reconcileSubscriptionLocked(subId, record, onlyRelayUrl = relayUrl)
            }
        }
        return commands
    }

    /**
     * 同時購読数の超過を知らされたとき。通知には購読IDがないので、直前に開いた購読が拒否されたとみなして
     * 待ちに戻し、そのリレーの上限を今開いている数より1つ下げる。
     */
    private fun onTooManySubscriptionsLocked(relayUrl: String) {
        val occupied = occupiedSlotsLocked(relayUrl)
        val lowered = (occupied - 1).coerceAtLeast(1)
        loweredSubscriptionLimits[relayUrl] = minOf(loweredSubscriptionLimits[relayUrl] ?: lowered, lowered)
        val subId = lastOpenedSubscriptions[relayUrl]
        val record = subId?.let(activeSubscriptions::get)
        val state = record?.relayStates?.get(relayUrl)
        if (record != null && state?.phase == RelaySubscriptionPhase.Sent) {
            record.relayStates[relayUrl] = SubscriptionStateMachine.prepareRetry(state)
        }
        fetchTraceLog {
            "LIMIT $relayUrl 同時購読数を ${subscriptionLimitLocked(relayUrl)} に下げる（開いていた数 $occupied）" +
                (if (state?.phase == RelaySubscriptionPhase.Sent) "。$subId を待ちに戻す" else "")
        }
    }

    private suspend fun sendSubscriptionCommands(
        commands: List<SubscriptionWireCommand>,
        fallbackHandles: List<ActiveRelayHandle> = emptyList(),
    ) {
        if (commands.isEmpty()) return
        val fallbackRelays = fallbackHandles.associateBy({ it.relay.url }, { it.relay })
        commands.forEach { command ->
            val relay = stateMutex.withLock { activeRelays[command.relayUrl]?.relay }
                ?: fallbackRelays[command.relayUrl]
            if (relay != null) {
                networkTraceLog { "[Repo] subscription command to ${command.relayUrl}: ${command.message.take(80)}" }
                val sent = relay.sendControl(command.message, command.generation)
                command.subscriptionId?.let { subscriptionId ->
                    val filters = command.filters
                    when {
                        !sent -> fetchTraceLog {
                            "DROP $subscriptionId → ${command.relayUrl} 接続の世代が違うため未送信（次の接続で送る）"
                        }
                        filters != null -> FetchTrace.req(subscriptionId, command.relayUrl, filters)
                        else -> FetchTrace.clientClose(subscriptionId, command.relayUrl)
                    }
                }
            } else {
                command.subscriptionId?.let { fetchTraceLog { "SKIP $it → ${command.relayUrl} 接続なしのため未送信" } }
            }
        }
    }

    private suspend fun closeRemovedRelayHandles(handles: List<ActiveRelayHandle>) {
        if (handles.isEmpty()) return
        delay(RELAY_CLOSE_GRACE_MS)
        handles.forEach { it.close() }
    }

    fun observedEventRelay(eventId: String): String? = eventRelayHints.get(eventId)

    fun observedEventRelays(eventId: String): Set<String> = eventRelayHints.getAll(eventId)
    /** メモリに無ければ有限の実取得で所在を確認する。任意のリレーで代用しない。 */
    suspend fun resolveEventRelay(eventId: String): String? {
        observedEventRelay(eventId)?.let { return it }
        if (!isFullEventId(eventId)) return null
        EventByIdFetcher(timeoutMillis = 2_500).fetch(setOf(eventId)) { }
        return observedEventRelay(eventId)
    }

    private suspend fun handleActiveRelayMessage(relayUrl: String, message: RelayMessage) {
        if (message is RelayMessage.Event) {
            eventRelayHints.observe(message.event.id, relayUrl)
            ReactionEventStore.observe(message.event, setOf(relayUrl))
        }
        val signals = mutableListOf<Pair<SubscriptionSessionImpl, SubscriptionSignal>>()
        var commands = emptyList<SubscriptionWireCommand>()
        var fetchToComplete: String? = null
        var retry: RetryRequest? = null
        var emitToLegacyBus = true

        stateMutex.withLock {
            relayLastMessageAt[relayUrl] = nowEpochSeconds()
            val subscriptionId = message.subscriptionIdOrNull()
            val record = subscriptionId?.let(activeSubscriptions::get)
            val state = record?.relayStates?.get(relayUrl)
            if (subscriptionId != null && (record == null || state == null)) {
                FetchTrace.stray(subscriptionId, relayUrl, message::class.simpleName.orEmpty())
            }
            when (message) {
                is RelayMessage.Notice -> {
                    FetchTrace.notice(relayUrl, message.message)
                    if (RelaySubscriptionSlots.isTooManySubscriptionsNotice(message.message)) {
                        onTooManySubscriptionsLocked(relayUrl)
                    }
                }
                is RelayMessage.Unknown -> FetchTrace.notice(relayUrl, message.raw)
                else -> Unit
            }
            if (subscriptionId != null && record != null && state != null) {
                when (message) {
                    is RelayMessage.Event -> {
                        val wasLive = state.phase == RelaySubscriptionPhase.Live
                        record.relayStates[relayUrl] = SubscriptionStateMachine.onEvent(state, message.event)
                        val isFirstDelivery = !record.deduplicateEvents || record.seenEventIds.add(message.event.id)
                        FetchTrace.event(subscriptionId, relayUrl, isFirstDelivery, wasLive)
                        emitToLegacyBus = isFirstDelivery
                        if (record.session != null && isFirstDelivery) {
                            signals += record.session to SubscriptionSignal.Event(
                                relayUrl = relayUrl,
                                event = message.event,
                                isLive = wasLive,
                            )
                        }
                    }
                    is RelayMessage.EndOfStoredEvents -> {
                        val replayGap = SubscriptionStateMachine.replayGap(state)
                        FetchTrace.eose(subscriptionId, relayUrl)
                        record.relayStates[relayUrl] = SubscriptionStateMachine.onEose(state)
                        record.session?.let { signals += it to SubscriptionSignal.Eose(relayUrl) }
                        if (record.behavior is SubscriptionBehavior.Live && replayGap != null) {
                            record.session?.let {
                                signals += it to SubscriptionSignal.Resumed(
                                    relayUrl = relayUrl,
                                    interruptedAt = replayGap.interruptedAt,
                                    replayOldestAt = replayGap.replayOldestAt,
                                )
                            }
                        }
                        record.fetchOutcomes[relayUrl] = RelayOutcome.Eose
                        commands = reconcileSubscriptionLocked(subscriptionId, record, onlyRelayUrl = relayUrl)
                        if (record.shouldCompleteFetchLocked()) {
                            record.fetchCompletionScheduled = true
                            fetchToComplete = subscriptionId
                        }
                    }
                    is RelayMessage.Closed -> {
                        val classification = classifyClosedReason(message.message)
                        FetchTrace.closed(subscriptionId, relayUrl, message.message)
                        val next = SubscriptionStateMachine.onClosed(
                            state = state,
                            structural = classification.structural,
                            maxStructuralRefusals = MAX_STRUCTURAL_REFUSALS,
                            closedAt = nowEpochSeconds(),
                        )
                        record.relayStates[relayUrl] = next
                        if (RelaySubscriptionSlots.occupies(state)) slotsFreedRelays += relayUrl
                        record.session?.let {
                            signals += it to SubscriptionSignal.Closed(
                                relayUrl = relayUrl,
                                reason = message.message,
                                retry = classification.disposition,
                            )
                        }
                        record.fetchOutcomes[relayUrl] = RelayOutcome.Closed(message.message)
                        if (record.shouldCompleteFetchLocked()) {
                            record.fetchCompletionScheduled = true
                            fetchToComplete = subscriptionId
                        } else if (
                            // 配信中の購読は回数で諦めず、間隔を延ばして再開を試み続ける。
                            record.behavior is SubscriptionBehavior.Live &&
                            classification.disposition == RetryDisposition.RetryWithBackoff
                        ) {
                            retry = RetryRequest(subscriptionId, relayUrl, next.refusalCount)
                        }
                    }
                    else -> Unit
                }
            }
            commands = commands + drainFreedSlotsLocked()
        }

        signals.forEach { (session, signal) -> session.emit(signal) }
        sendSubscriptionCommands(commands)
        if (emitToLegacyBus) bus.emit(RelayEnvelope(relayUrl, message))
        retry?.let { request ->
            scope.launch {
                delay(
                    SubscriptionStateMachine.liveRetryDelayMillis(
                        attempt = request.attempt,
                        baseDelayMillis = RETRY_BASE_DELAY_MS,
                        quickAttempts = LIVE_QUICK_RETRIES,
                        slowDelayMillis = LIVE_SLOW_RETRY_DELAY_MS,
                    ),
                )
                retryClosedSubscription(request)
            }
        }
        fetchToComplete?.let { completeFetch(it, timedOut = false) }
    }

    private suspend fun retryClosedSubscription(request: RetryRequest) {
        val commands = stateMutex.withLock {
            val record = activeSubscriptions[request.subscriptionId] ?: return@withLock emptyList()
            if (request.relayUrl !in record.target.urls(enabledRelayUrls)) return@withLock emptyList()
            val state = record.relayStates[request.relayUrl] ?: return@withLock emptyList()
            if (state.phase != RelaySubscriptionPhase.Closed || state.refusalCount != request.attempt) {
                return@withLock emptyList()
            }
            record.relayStates[request.relayUrl] = SubscriptionStateMachine.prepareRetry(state)
            reconcileSubscriptionLocked(request.subscriptionId, record, onlyRelayUrl = request.relayUrl) +
                drainFreedSlotsLocked()
        }
        sendSubscriptionCommands(commands)
    }

    private suspend fun completeFetch(subscriptionId: String, timedOut: Boolean) {
        val completion = stateMutex.withLock {
            val record = activeSubscriptions[subscriptionId] ?: return@withLock null
            val expected = record.fetchExpectedRelays ?: return@withLock null
            if (record.fetchCompleted) return@withLock null
            if (!timedOut && !expected.all { it in record.fetchOutcomes }) {
                record.fetchCompletionScheduled = false
                return@withLock null
            }
            if (timedOut) {
                expected.filterNot { it in record.fetchOutcomes }
                    .forEach { record.fetchOutcomes[it] = RelayOutcome.TimedOut }
            }
            record.fetchCompleted = true
            FetchCompletion(
                session = record.session ?: return@withLock null,
                outcomes = expected.associateWith { record.fetchOutcomes[it] ?: RelayOutcome.TimedOut },
            )
        } ?: return

        FetchTrace.fetchCompleted(
            subscriptionId,
            completion.outcomes,
            timedOut || completion.outcomes.values.any { it is RelayOutcome.TimedOut },
        )
        completion.session.emit(
            SubscriptionSignal.FetchCompleted(
                outcomes = completion.outcomes,
                timedOut = timedOut || completion.outcomes.values.any { it is RelayOutcome.TimedOut },
            ),
        )
        closeActiveSubscription(subscriptionId, buildCloseMessage(subscriptionId))
    }

    private fun createTemporaryRelayHandle(relay: NostrRelay): TemporaryRelayHandle {
        val messageJob = scope.launch {
            try {
                relay.messages.collect { message ->
                    when (message) {
                        is RelayMessage.Closed -> networkTraceLog {
                            "[Repo] CLOSED from ${relay.url} subId=${message.subscriptionId} reason=${message.message}"
                        }
                        else -> networkTraceLog { "[Repo] message from ${relay.url}: $message" }
                    }
                    if (message is RelayMessage.Event) {
                        eventRelayHints.observe(message.event.id, relay.url)
                        ReactionEventStore.observe(message.event, setOf(relay.url))
                    }
                    bus.emit(RelayEnvelope(relay.url, message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] temporary messages collector error for ${relay.url}: ${e::class.simpleName}: ${e.message}")
            }
        }
        val connectedJob = scope.launch {
            try {
                relay.connected.collect { generation ->
                    val messages = stateMutex.withLock {
                        temporaryRelayGenerations[relay.url] = generation
                        temporarySubscriptions.mapNotNull { (subId, filterAndRelay) ->
                            val (storedFilter, storedRelayUrl) = filterAndRelay
                            if (storedRelayUrl == relay.url) {
                                buildReqMessage(subId, storedFilter)
                            } else {
                                null
                            }
                        }
                    }
                    networkTraceLog { "[Repo] temporary relay connected: ${relay.url} resending ${messages.size} subscriptions" }
                    messages.forEach { relay.sendControl(it, generation) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appLog("[NostrRepository] temporary connected collector error for ${relay.url}: ${e::class.simpleName}: ${e.message}")
            }
        }
        return TemporaryRelayHandle(relay, listOf(messageJob, connectedJob))
    }

    suspend fun subscribe(subscriptionId: String, filter: NostrFilter, relayUrl: String? = null): Set<String> =
        subscribe(
            subscriptionId = subscriptionId,
            filters = listOf(filter),
            target = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled,
        )

    suspend fun subscribe(subscriptionId: String, filter: NostrFilter, target: RelayTarget): Set<String> =
        subscribe(subscriptionId, listOf(filter), target)

    suspend fun subscribe(
        subscriptionId: String,
        filters: List<NostrFilter>,
        target: RelayTarget = RelayTarget.AllEnabled,
    ): Set<String> {
        require(filters.isNotEmpty()) { "購読には1件以上のフィルターが必要です" }
        networkTraceLog { "[Repo] subscribe() subId='$subscriptionId' target=$target filterCount=${filters.size}" }
        val (targetUrls, commands, removedHandles, newHandles) = stateMutex.withLock {
            val urls = target.urls(enabledRelayUrls).toSet()
            val existing = activeSubscriptions[subscriptionId]
            check(existing?.session == null) {
                "セッション管理中の購読IDは互換APIから更新できません: $subscriptionId"
            }
            val record = existing ?: ActiveSubscriptionRecord(
                filters = filters,
                target = target,
                behavior = SubscriptionBehavior.Live,
            ).also { activeSubscriptions[subscriptionId] = it }
            record.filters = filters
            record.target = target
            val commands = reconcileSubscriptionLocked(subscriptionId, record) + drainFreedSlotsLocked()
            val (removed, added) = reconcileActiveRelaysLocked()
            SubscriptionUpdateWork(urls, commands, removed, added)
        }
        newHandles.forEach { it.relay.connect(scope) }
        sendSubscriptionCommands(commands, removedHandles)
        closeRemovedRelayHandles(removedHandles)
        replayCachedToLegacySubscription(subscriptionId, filters, targetUrls)
        return targetUrls
    }

    suspend fun openSubscription(spec: SubscriptionSpec): SubscriptionSession {
        require(spec.id.isNotBlank()) { "購読IDは空にできません" }
        require(spec.filters.isNotEmpty()) { "購読には1件以上のフィルターが必要です" }
        val session = SubscriptionSessionImpl(
            id = spec.id,
            lossless = spec.behavior is SubscriptionBehavior.Fetch,
        )
        val (commands, removedHandles, newHandles, completeImmediately, targetRelayUrls) = stateMutex.withLock {
            check(spec.id !in activeSubscriptions) { "購読IDはすでに使用されています: ${spec.id}" }
            val targetUrls = spec.target.urls(enabledRelayUrls).toSet()
            val record = ActiveSubscriptionRecord(
                filters = spec.filters,
                target = spec.target,
                behavior = spec.behavior,
                deduplicateEvents = spec.deduplicateEvents,
                replayCachedEvents = spec.replayCachedEvents,
                session = session,
                fetchExpectedRelays = if (spec.behavior is SubscriptionBehavior.Fetch) targetUrls else null,
            )
            activeSubscriptions[spec.id] = record
            val commands = reconcileSubscriptionLocked(spec.id, record) + drainFreedSlotsLocked()
            val (removed, added) = reconcileActiveRelaysLocked()
            SessionOpenWork(
                commands = commands,
                removedHandles = removed,
                newHandles = added,
                completeImmediately = spec.behavior is SubscriptionBehavior.Fetch && targetUrls.isEmpty(),
                targetRelayUrls = targetUrls,
            )
        }
        try {
            if (!completeImmediately && spec.behavior is SubscriptionBehavior.Fetch) {
                restartFetchTimeout(spec.id, session, spec.behavior)
            }
            newHandles.forEach { it.relay.connect(scope) }
            sendSubscriptionCommands(commands, removedHandles)
            closeRemovedRelayHandles(removedHandles)
            if (spec.replayCachedEvents) replayCachedToSession(session, spec.filters, targetRelayUrls)
            if (completeImmediately) completeFetch(spec.id, timedOut = false)
        } catch (e: Throwable) {
            // 登録後に呼び出し元がキャンセルされると、session を受け取れないまま登録だけが残り、
            // 同じIDでの再オープンが「使用済み」で失敗し続ける。登録を巻き戻してから再送出する。
            appLog("[NostrRepository] openSubscription aborted after register id=${spec.id} cause=${e::class.simpleName}")
            withContext(NonCancellable) {
                // 同じIDが別の呼び出しに再利用されていた場合に、その購読を閉じないよう、所有者を確認して閉じる。
                closeSubscriptionSession(session)
                removedHandles.forEach { it.close() }
            }
            throw e
        }
        return session
    }

    internal suspend fun updateSubscriptionSession(
        session: SubscriptionSessionImpl,
        filters: List<NostrFilter>,
        target: RelayTarget,
    ) {
        require(filters.isNotEmpty()) { "購読には1件以上のフィルターが必要です" }
        var replayCache = true
        val (targetUrls, commands, removedHandles, newHandles) = stateMutex.withLock {
            val record = activeSubscriptions[session.id]
                ?: error("購読はすでに終了しています: ${session.id}")
            check(record.session === session) { "購読セッションが一致しません: ${session.id}" }
            check(record.behavior is SubscriptionBehavior.Live) { "有限取得セッションは更新できません" }
            record.filters = filters
            record.target = target
            replayCache = record.replayCachedEvents
            val commands = reconcileSubscriptionLocked(session.id, record) + drainFreedSlotsLocked()
            val (removed, added) = reconcileActiveRelaysLocked()
            SubscriptionUpdateWork(target.urls(enabledRelayUrls).toSet(), commands, removed, added)
        }
        newHandles.forEach { it.relay.connect(scope) }
        sendSubscriptionCommands(commands, removedHandles)
        closeRemovedRelayHandles(removedHandles)
        if (replayCache) replayCachedToSession(session, filters, targetUrls)
    }

    private suspend fun replayCachedToLegacySubscription(
        subscriptionId: String,
        filters: List<NostrFilter>,
        targetRelayUrls: Set<String>,
    ) {
        val cached = cachedEventsNotYetDelivered(subscriptionId, filters, targetRelayUrls)
        FetchTrace.cacheReplay(subscriptionId, cached.size)
        cached.forEach { event ->
            bus.emit(RelayEnvelope(CACHE_RELAY_URL, RelayMessage.Event(subscriptionId, event)))
        }
    }

    private suspend fun replayCachedToSession(
        session: SubscriptionSessionImpl,
        filters: List<NostrFilter>,
        targetRelayUrls: Set<String>,
    ) {
        val cached = cachedEventsNotYetDelivered(session.id, filters, targetRelayUrls)
        FetchTrace.cacheReplay(session.id, cached.size)
        cached.forEach { event ->
            session.emit(
                SubscriptionSignal.Event(
                    relayUrl = CACHE_RELAY_URL,
                    event = event,
                    isLive = false,
                ),
            )
        }
    }

    private suspend fun cachedEventsNotYetDelivered(
        subscriptionId: String,
        filters: List<NostrFilter>,
        targetRelayUrls: Set<String>,
    ): List<NostrEvent> {
        val cached = ReactionEventStore.matching(filters, targetRelayUrls)
        if (cached.isEmpty()) return emptyList()
        return stateMutex.withLock {
            val record = activeSubscriptions[subscriptionId] ?: return@withLock emptyList()
            cached.filter { event -> !record.deduplicateEvents || record.seenEventIds.add(event.id) }
        }
    }

    internal suspend fun closeSubscriptionSession(session: SubscriptionSessionImpl) {
        val ownsSubscription = stateMutex.withLock {
            activeSubscriptions[session.id]?.session === session
        }
        if (ownsSubscription) {
            closeActiveSubscription(session.id, buildCloseMessage(session.id))
        } else {
            session.finish()
        }
    }

    suspend fun subscribeTemporaryRelay(subscriptionId: String, filter: NostrFilter, relayUrl: String) {
        networkTraceLog { "[Repo] subscribeTemporaryRelay() subId='$subscriptionId' relay=$relayUrl filter=$filter" }
        // 追加と世代の読み取りを同じ排他区間で行う。接続済みならその世代で送り、
        // まだなら接続時の再同期がこの購読も含めて送る（二重に送らない）。
        val (existingHandle, generation) = stateMutex.withLock {
            temporarySubscriptions[subscriptionId] = Pair(filter, relayUrl)
            temporaryRelays[relayUrl] to temporaryRelayGenerations[relayUrl]
        }
        var createdRelay = false
        val handle = existingHandle ?: run {
            networkTraceLog { "[Repo] connecting to temporary relay: $relayUrl" }
            val newRelay = NostrRelay(relayUrl, httpClient)
            val newHandle = createTemporaryRelayHandle(newRelay)
            val storedHandle = stateMutex.withLock {
                temporaryRelays[relayUrl] ?: newHandle.also {
                    temporaryRelays[relayUrl] = it
                }
            }
            if (storedHandle === newHandle) {
                createdRelay = true
                newRelay.connect(scope)
            } else {
                newHandle.close()
            }
            storedHandle
        }
        if (!createdRelay && generation != null) {
            handle.relay.sendControl(buildReqMessage(subscriptionId, filter), generation)
        }
    }

    /** サブスクリプションを解除し、リレーに CLOSE を送る */
    fun close(subscriptionId: String) {
        networkTraceLog { "[Repo] close() subId='$subscriptionId' relayCount=${relayCount}" }
        val msg = buildCloseMessage(subscriptionId)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            closeActiveSubscription(subscriptionId, msg)
        }
    }

    fun closeTemporaryRelay(subscriptionId: String) {
        networkTraceLog { "[Repo] closeTemporaryRelay() subId='$subscriptionId'" }
        val msg = buildCloseMessage(subscriptionId)
        scope.launch {
            val (targets, handlesToClose) = stateMutex.withLock {
                val selection = removeTemporarySubscription(
                    subscriptionId = subscriptionId,
                    subscriptions = temporarySubscriptions,
                    relays = temporaryRelays,
                )
                selection?.handleToClose?.let { temporaryRelayGenerations.remove(it.relay.url) }
                listOfNotNull(selection?.relayHandle?.relay) to
                    listOfNotNull(selection?.handleToClose)
            }
            targets.forEach { relay -> relay.connectedGeneration()?.let { relay.sendControl(msg, it) } }
            handlesToClose.forEach { it.close() }
        }
    }

    /** サブスクリプション解除を呼び出し元のコルーチン内で送信する。 */
    suspend fun closeSuspending(subscriptionId: String) {
        networkTraceLog { "[Repo] closeSuspending() subId='$subscriptionId' relayCount=${relayCount}" }
        val msg = buildCloseMessage(subscriptionId)
        closeActiveSubscription(subscriptionId, msg)
    }

    private suspend fun closeActiveSubscription(subscriptionId: String, closeMessage: String) {
        val (commands, removedHandles, session) = stateMutex.withLock {
            val record = activeSubscriptions.remove(subscriptionId)
            record?.relayStates?.forEach { (relayUrl, state) ->
                if (RelaySubscriptionSlots.occupies(state)) slotsFreedRelays += relayUrl
            }
            val commands = record?.relayStates
                .orEmpty()
                .filterValues { it.sentFilters != null }
                .keys
                .map { relayUrl ->
                    SubscriptionWireCommand(
                        relayUrl = relayUrl,
                        message = closeMessage,
                        generation = relayConnectionGenerations[relayUrl] ?: 0L,
                        subscriptionId = subscriptionId,
                    )
                } + drainFreedSlotsLocked()
            val (removed, _) = reconcileActiveRelaysLocked()
            Triple(commands, removed, record?.session)
        }
        FetchTrace.subscriptionEnded(subscriptionId)
        session?.timeoutJob?.cancel()
        sendSubscriptionCommands(commands, removedHandles)
        closeRemovedRelayHandles(removedHandles)
        session?.finish()
    }

    /** 現在接続中のリレー数 */
    val relayCount: Int get() = activeRelayCount.value

    suspend fun targetRelayUrls(target: RelayTarget): Set<String> =
        stateMutex.withLock { target.urls(enabledRelayUrls).toSet() }

    /** セッション開始前に、保存済みリレー設定が購読ルーティングへ反映されるのを待つ。 */
    internal suspend fun awaitRelayRouting(urls: Set<String>) {
        routingRelayUrls.first { it == urls }
    }

    /** 署名済みイベントを有効な全リレーへ送信し、全送信先の結果を返す。 */
    suspend fun publish(event: NostrEvent): RelayPublishResult {
        val targets = RelayStore.writableRelayUrlsSnapshot()
        check(targets.isNotEmpty()) { "書き込み可能なリレーがありません" }
        val result = publishToRelaysWithResult(event, targets)
        check(result.succeededRelays.isNotEmpty()) {
            "すべてのリレーへの送信に失敗しました: ${result.failedRelays.keys.joinToString()}"
        }
        ReactionEventStore.observe(event, result.succeededRelays)
        return result
    }

    /**
     * 有効な書き込みリレーのうち最初の1件へ送信できた時点で戻る。
     * 残りのリレーへの送信はリポジトリのスコープで継続するため、未接続リレーの
     * タイムアウトが投稿完了後のUIを塞がない。
     */
    suspend fun publishUntilFirstSuccess(event: NostrEvent): RelayPublishResult {
        val targets = RelayStore.writableRelayUrlsSnapshot()
        check(targets.isNotEmpty()) { "書き込み可能なリレーがありません" }
        val result = publishToRelaysUntilFirstSuccess(
            event = event,
            relayUrls = targets,
            onRelayResult = { relayResult ->
                if (relayResult.succeededRelays.isNotEmpty()) {
                    ReactionEventStore.observe(event, relayResult.succeededRelays)
                }
            },
        )
        check(result.succeededRelays.isNotEmpty()) {
            "すべてのリレーへの送信に失敗しました: ${result.failedRelays.keys.joinToString()}"
        }
        return result
    }

    /** 署名済みイベントを指定リレーにだけ送信する。未接続リレーは一時接続して送る。 */
    suspend fun publishToRelays(event: NostrEvent, relayUrls: Collection<String>) {
        val result = publishToRelaysWithResult(event, relayUrls)
        check(result.failedRelays.isEmpty()) {
            "接続できないリレーがあります: ${result.failedRelays.keys.joinToString()}"
        }
    }

    /** 指定リレーがイベントを受理したことを OK 応答で確認する。 */
    suspend fun publishToRelaysAndAwaitAcceptance(
        event: NostrEvent,
        relayUrls: Collection<String>,
    ) {
        val result = publishToRelaysWithResult(event, relayUrls, awaitAcceptance = true)
        check(result.failedRelays.isEmpty()) {
            "受理されなかったリレーがあります: ${result.failedRelays.entries.joinToString { "${it.key} (${it.value})" }}"
        }
    }

    internal suspend fun publishToRelaysWithResult(
        event: NostrEvent,
        relayUrls: Collection<String>,
        awaitAcceptance: Boolean = false,
        respectRelayWritePolicy: Boolean = true,
    ): RelayPublishResult = coroutineScope {
        val requestedTargets = relayUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val (targets, rejectedTargets) = requestedTargets.partition { url ->
            !respectRelayWritePolicy || RelayStore.allowsWritingTo(url)
        }
        if (targets.isEmpty()) {
            return@coroutineScope RelayPublishResult(
                succeededRelays = emptySet(),
                failedRelays = rejectedTargets.associateWith { "NIP-65でread専用に設定されています" },
                rejectedRelays = rejectedTargets.toSet(),
            )
        }

        val message = buildEventMessage(event)
        networkTraceLog { "[Repo] publish event id=${event.id.take(8)} to ${targets.size} relays" }

        val results = targets.map { url ->
            async {
                url to runCatching {
                    val activeRelay = stateMutex.withLock { activeRelays[url]?.relay }
                    if (activeRelay != null) {
                        check(withTimeoutOrNull(PUBLISH_TIMEOUT_MS) {
                            if (awaitAcceptance) {
                                val response = activeRelay.sendEventAndAwaitOk(message, event.id)
                                if (!response.accepted) throw relayRejection(response.message)
                            } else {
                                activeRelay.sendAndAwait(message)
                            }
                            true
                        } == true) { "送信がタイムアウトしました" }
                    } else {
                        val relay = NostrRelay(url, httpClient)
                        try {
                            // 一時リレーの再接続ループをこの publish の子ジョブにすると、
                            // タイムアウト後も子ジョブの終了待ちで publish 自体が戻らないことがある。
                            // リポジトリのライフサイクルへ分離し、finally で明示的に停止する。
                            relay.connect(scope)
                            check(withTimeoutOrNull(PUBLISH_TIMEOUT_MS) {
                                relay.connected.first()
                                if (awaitAcceptance) {
                                    val response = relay.sendEventAndAwaitOk(message, event.id)
                                    if (!response.accepted) throw relayRejection(response.message)
                                } else {
                                    relay.sendAndAwait(message)
                                }
                                true
                            } == true) { "接続または送信がタイムアウトしました" }
                        } finally {
                            relay.disconnect()
                        }
                    }
                }.onFailure { error ->
                    appLog("[Repo] publish failed for $url: ${error::class.simpleName}: ${error.message}")
                }
            }
        }.awaitAll()

        RelayPublishResult(
            rejectedRelays = rejectedTargets.toSet() + results.filter { it.second.exceptionOrNull() is RelayRejectedException }.map { it.first },
            succeededRelays = results.filter { it.second.isSuccess }.mapTo(linkedSetOf()) { it.first },
            failedRelays = buildMap {
                rejectedTargets.forEach { url ->
                    put(url, "NIP-65でread専用に設定されています")
                }
                results.forEach { (url, result) ->
                    result.exceptionOrNull()?.let { error ->
                        put(url, error.message ?: "送信に失敗しました")
                    }
                }
            },
        )
    }

    /**
     * 最初の1リレーへの送信成功で戻る。残りのリレーへの送信はリポジトリの
     * スコープで継続するため、フォローなどの楽観的UIを遅いリレーがブロックしない。
     */
    internal suspend fun publishToRelaysUntilFirstSuccess(
        event: NostrEvent,
        relayUrls: Collection<String>,
        onRelayResult: suspend (RelayPublishResult) -> Unit = {},
        respectRelayWritePolicy: Boolean = true,
        awaitAcceptance: Boolean = false,
    ): RelayPublishResult {
        val targets = relayUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return RelayPublishResult(emptySet(), emptyMap())

        val completion = CompletableDeferred<RelayPublishResult>()
        val resultMutex = Mutex()
        val failures = linkedMapOf<String, String>()
        var completedCount = 0

        targets.forEach { relayUrl ->
            scope.launch {
                val result = publishToRelaysWithResult(
                    event = event,
                    relayUrls = listOf(relayUrl),
                    awaitAcceptance = awaitAcceptance,
                    respectRelayWritePolicy = respectRelayWritePolicy,
                )
                runCatching { onRelayResult(result) }
                    .onFailure { error ->
                        appLog("[Repo] publish result callback failed: ${error::class.simpleName}: ${error.message}")
                    }
                val completedResult = resultMutex.withLock {
                    completedCount++
                    failures.putAll(result.failedRelays)
                    when {
                        result.succeededRelays.isNotEmpty() -> RelayPublishResult(
                            succeededRelays = result.succeededRelays,
                            failedRelays = failures.toMap(),
                        )
                        completedCount == targets.size -> RelayPublishResult(
                            succeededRelays = emptySet(),
                            failedRelays = failures.toMap(),
                        )
                        else -> null
                    }
                }
                if (completedResult != null) completion.complete(completedResult)
            }
        }

        return completion.await()
    }

    fun events(subscriptionId: String): Flow<NostrEvent> =
        bus
            .filter { it.message is RelayMessage.Event && it.message.subscriptionId == subscriptionId }
            .map { (it.message as RelayMessage.Event).event }

    /** 指定サブスクリプションの EOSE または CLOSED（リレー側でサブスクが終了）を通知する Flow */
    fun eose(subscriptionId: String): Flow<Unit> =
        bus
            .filter { msg ->
                (msg.message is RelayMessage.EndOfStoredEvents && msg.message.subscriptionId == subscriptionId) ||
                (msg.message is RelayMessage.Closed && msg.message.subscriptionId == subscriptionId)
            }
            .map { }

    fun eoseRelays(subscriptionId: String): Flow<String> =
        bus
            .filter { envelope ->
                (envelope.message is RelayMessage.EndOfStoredEvents &&
                    envelope.message.subscriptionId == subscriptionId) ||
                    (envelope.message is RelayMessage.Closed &&
                        envelope.message.subscriptionId == subscriptionId)
            }
            .map { it.relayUrl }

    fun endOfStoredEvents(subscriptionId: String): Flow<Unit> =
        bus
            .filter { it.message is RelayMessage.EndOfStoredEvents && it.message.subscriptionId == subscriptionId }
            .map { }

    /** リレーが CLOSED を送ってきたときだけ通知する Flow */
    fun closed(subscriptionId: String): Flow<Unit> =
        bus
            .filter { it.message is RelayMessage.Closed && it.message.subscriptionId == subscriptionId }
            .map { }

    fun closedMessages(subscriptionId: String): Flow<RelayMessage.Closed> =
        bus
            .filter { it.message is RelayMessage.Closed && it.message.subscriptionId == subscriptionId }
            .map { it.message as RelayMessage.Closed }

    private const val PUBLISH_TIMEOUT_MS = 10_000L
    private const val RELAY_CLOSE_GRACE_MS = 100L
    private const val RETRY_BASE_DELAY_MS = 1_000L
    private const val LIVE_QUICK_RETRIES = 3
    private const val LIVE_SLOW_RETRY_DELAY_MS = 5L * 60L * 1_000L
    private const val MAX_STRUCTURAL_REFUSALS = 3
    private const val CACHE_RELAY_URL = "local://reaction-cache"

    private fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds
}

private data class RelayEnvelope(
    val relayUrl: String,
    val message: RelayMessage,
)

internal class SubscriptionSessionImpl(
    override val id: String,
    lossless: Boolean = false,
) : SubscriptionSession {
    // finite fetch は EOSE/完了より前のイベントを欠落させてはならない。openSubscription は
    // キャッシュ再生後に session を返すため、有限取得だけ無制限キューで確実に保持する。
    private val channel = Channel<SubscriptionSignal>(
        capacity = if (lossless) Channel.UNLIMITED else 512,
    )
    override val signals: Flow<SubscriptionSignal> = channel.receiveAsFlow()
    internal var timeoutJob: Job? = null

    override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) {
        NostrRepository.updateSubscriptionSession(this, filters, target)
    }

    override suspend fun close() {
        NostrRepository.closeSubscriptionSession(this)
    }

    internal fun emit(signal: SubscriptionSignal) {
        if (channel.trySend(signal).isFailure) {
            appLog("[SubscriptionSession] signal buffer overflow or closed: $id signal=${signal::class.simpleName}")
        }
    }

    internal fun finish() {
        timeoutJob?.cancel()
        timeoutJob = null
        channel.close()
    }
}

private data class ActiveSubscriptionRecord(
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
) {
    fun shouldCompleteFetchLocked(): Boolean {
        val expected = fetchExpectedRelays ?: return false
        return !fetchCompleted && !fetchCompletionScheduled && expected.all { it in fetchOutcomes }
    }
}

private class BoundedIdSet(
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

private data class SubscriptionWireCommand(
    val relayUrl: String,
    val message: String,
    /** 送ると決めた時点の接続世代。リレーの現在の接続と違えば送らない。 */
    val generation: Long,
    /** 通信ログ用。REQ ならフィルター、CLOSE なら null。 */
    val subscriptionId: String? = null,
    val filters: List<NostrFilter>? = null,
)

private data class SubscriptionUpdateWork(
    val targetUrls: Set<String>,
    val commands: List<SubscriptionWireCommand>,
    val removedHandles: List<ActiveRelayHandle>,
    val newHandles: List<ActiveRelayHandle>,
)

private data class SessionOpenWork(
    val commands: List<SubscriptionWireCommand>,
    val removedHandles: List<ActiveRelayHandle>,
    val newHandles: List<ActiveRelayHandle>,
    val completeImmediately: Boolean,
    val targetRelayUrls: Set<String>,
)

private data class RetryRequest(
    val subscriptionId: String,
    val relayUrl: String,
    val attempt: Int,
)

private data class FetchCompletion(
    val session: SubscriptionSessionImpl,
    val outcomes: Map<String, RelayOutcome>,
)

private fun RelayMessage.subscriptionIdOrNull(): String? = when (this) {
    is RelayMessage.Event -> subscriptionId
    is RelayMessage.EndOfStoredEvents -> subscriptionId
    is RelayMessage.Closed -> subscriptionId
    else -> null
}

private data class TemporaryRelayHandle(
    val relay: NostrRelay,
    val collectorJobs: List<kotlinx.coroutines.Job>,
) {
    fun close() {
        relay.disconnect()
        collectorJobs.forEach { it.cancel() }
    }
}

internal data class TemporaryRelayCloseSelection<T>(
    val relayHandle: T?,
    val handleToClose: T?,
)

/**
 * 一時購読を登録解除する。未登録のIDは他の一時購読・リレーに影響しない。
 */
internal fun <F, T> removeTemporarySubscription(
    subscriptionId: String,
    subscriptions: MutableMap<String, Pair<F, String>>,
    relays: MutableMap<String, T>,
): TemporaryRelayCloseSelection<T>? {
    val relayUrl = subscriptions.remove(subscriptionId)?.second ?: return null
    val relayHandle = relays[relayUrl]
    val handleToClose = if (subscriptions.values.none { it.second == relayUrl }) {
        relays.remove(relayUrl)
    } else {
        null
    }
    return TemporaryRelayCloseSelection(relayHandle, handleToClose)
}

private data class ActiveRelayHandle(
    val relay: NostrRelay,
    val collectorJobs: List<kotlinx.coroutines.Job>,
) {
    fun close() {
        relay.disconnect()
        collectorJobs.forEach { it.cancel() }
    }
}
