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
import com.nostr.torinos.util.networkTraceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RelayTarget {
    data object AllEnabled : RelayTarget
    data class Single(val url: String) : RelayTarget
    data class Explicit(val urls: Set<String>) : RelayTarget
}

data class RelayPublishResult(
    val succeededRelays: Set<String>,
    val failedRelays: Map<String, String>,
) {
    val successCount: Int get() = succeededRelays.size
    val failureCount: Int get() = failedRelays.size
    val totalCount: Int get() = successCount + failureCount
}

private fun RelayTarget.urls(enabledRelayUrls: List<String>): List<String> = when (this) {
    RelayTarget.AllEnabled -> enabledRelayUrls
    is RelayTarget.Single -> listOf(url).filter { it in enabledRelayUrls }
    is RelayTarget.Explicit -> urls.filter { it in enabledRelayUrls }
}

object NostrRepository {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            loggingExceptionHandler("NostrRepository", "Uncaught coroutine exception"),
    )

    /** 全リレーからのメッセージを集約するバス */
    private val bus = MutableSharedFlow<RelayEnvelope>(extraBufferCapacity = 512)

    internal val httpClient = createHttpClient()
    private val stateMutex = Mutex()
    private val activeRelays = mutableMapOf<String, ActiveRelayHandle>()
    private var enabledRelayUrls: List<String> = RelayStore.defaults.filter { it.enabled }.map { it.url }
    private val activeSubscriptions = mutableMapOf<String, ActiveSubscriptionRecord>()
    private val relayConnectionGenerations = mutableMapOf<String, Long>()
    private val temporaryRelays = mutableMapOf<String, TemporaryRelayHandle>()
    private val temporarySubscriptions = mutableMapOf<String, Pair<NostrFilter, String>>()
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
                        }
                        val (removed, added) = reconcileActiveRelaysLocked()
                        Triple(commands, removed, added)
                    }
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
                relay.connected.collect {
                    val commands = stateMutex.withLock {
                        val generation = (relayConnectionGenerations[relay.url] ?: 0L) + 1L
                        relayConnectionGenerations[relay.url] = generation
                        activeSubscriptions.flatMap { (subId, record) ->
                            record.relayStates[relay.url]?.let { state ->
                                record.relayStates[relay.url] =
                                    SubscriptionStateMachine.onDisconnected(state, generation)
                            }
                            reconcileSubscriptionLocked(subId, record, onlyRelayUrl = relay.url)
                        }
                    }
                    networkTraceLog { "[Repo] relay connected: ${relay.url} syncing ${commands.size} subscriptions" }
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
            .mapNotNull { url -> activeRelays.remove(url) }
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
            val result = SubscriptionStateMachine.reconcile(
                state = record.relayStates[relayUrl],
                desiredFilters = desiredFilters,
                connectionGeneration = generation,
            )
            if (result.state == null) {
                record.relayStates.remove(relayUrl)
            } else {
                record.relayStates[relayUrl] = result.state
            }
            when (val command = result.command) {
                is SubscriptionCommandDecision.Open -> SubscriptionWireCommand(
                    relayUrl = relayUrl,
                    message = buildReqMessage(subscriptionId, command.filters),
                )
                SubscriptionCommandDecision.Close -> SubscriptionWireCommand(
                    relayUrl = relayUrl,
                    message = buildCloseMessage(subscriptionId),
                )
                null -> null
            }
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
                relay.send(command.message)
            }
        }
    }

    private suspend fun closeRemovedRelayHandles(handles: List<ActiveRelayHandle>) {
        if (handles.isEmpty()) return
        delay(RELAY_CLOSE_GRACE_MS)
        handles.forEach { it.close() }
    }

    private suspend fun handleActiveRelayMessage(relayUrl: String, message: RelayMessage) {
        val signals = mutableListOf<Pair<SubscriptionSessionImpl, SubscriptionSignal>>()
        var commands = emptyList<SubscriptionWireCommand>()
        var fetchToComplete: String? = null
        var retry: RetryRequest? = null
        var emitToLegacyBus = true

        stateMutex.withLock {
            val subscriptionId = message.subscriptionIdOrNull()
            val record = subscriptionId?.let(activeSubscriptions::get)
            val state = record?.relayStates?.get(relayUrl)
            if (subscriptionId != null && record != null && state != null) {
                when (message) {
                    is RelayMessage.Event -> {
                        val wasLive = state.phase == RelaySubscriptionPhase.Live
                        record.relayStates[relayUrl] = SubscriptionStateMachine.onEvent(state)
                        val isFirstDelivery = record.seenEventIds.add(message.event.id)
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
                        record.relayStates[relayUrl] = SubscriptionStateMachine.onEose(state)
                        record.session?.let { signals += it to SubscriptionSignal.Eose(relayUrl) }
                        record.fetchOutcomes[relayUrl] = RelayOutcome.Eose
                        commands = reconcileSubscriptionLocked(subscriptionId, record, onlyRelayUrl = relayUrl)
                        if (record.shouldCompleteFetchLocked()) {
                            record.fetchCompletionScheduled = true
                            fetchToComplete = subscriptionId
                        }
                    }
                    is RelayMessage.Closed -> {
                        val classification = classifyClosedReason(message.message)
                        val next = SubscriptionStateMachine.onClosed(
                            state = state,
                            structural = classification.structural,
                            maxStructuralRefusals = MAX_STRUCTURAL_REFUSALS,
                        )
                        record.relayStates[relayUrl] = next
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
                            record.behavior is SubscriptionBehavior.Live &&
                            classification.disposition == RetryDisposition.RetryWithBackoff &&
                            next.refusalCount < MAX_TRANSIENT_RETRIES
                        ) {
                            retry = RetryRequest(subscriptionId, relayUrl, next.refusalCount)
                        }
                    }
                    else -> Unit
                }
            }
        }

        signals.forEach { (session, signal) -> session.emit(signal) }
        sendSubscriptionCommands(commands)
        if (emitToLegacyBus) bus.emit(RelayEnvelope(relayUrl, message))
        retry?.let { request ->
            scope.launch {
                delay(RETRY_BASE_DELAY_MS * (1L shl (request.attempt - 1).coerceAtLeast(0)))
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
            reconcileSubscriptionLocked(request.subscriptionId, record, onlyRelayUrl = request.relayUrl)
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
                relay.connected.collect {
                    val messages = stateMutex.withLock {
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
                    messages.forEach { relay.send(it) }
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
            val commands = reconcileSubscriptionLocked(subscriptionId, record)
            val (removed, added) = reconcileActiveRelaysLocked()
            SubscriptionUpdateWork(urls, commands, removed, added)
        }
        newHandles.forEach { it.relay.connect(scope) }
        sendSubscriptionCommands(commands, removedHandles)
        closeRemovedRelayHandles(removedHandles)
        return targetUrls
    }

    suspend fun openSubscription(spec: SubscriptionSpec): SubscriptionSession {
        require(spec.id.isNotBlank()) { "購読IDは空にできません" }
        require(spec.filters.isNotEmpty()) { "購読には1件以上のフィルターが必要です" }
        val session = SubscriptionSessionImpl(spec.id)
        val (commands, removedHandles, newHandles, completeImmediately) = stateMutex.withLock {
            check(spec.id !in activeSubscriptions) { "購読IDはすでに使用されています: ${spec.id}" }
            val targetUrls = spec.target.urls(enabledRelayUrls).toSet()
            val record = ActiveSubscriptionRecord(
                filters = spec.filters,
                target = spec.target,
                behavior = spec.behavior,
                session = session,
                fetchExpectedRelays = if (spec.behavior is SubscriptionBehavior.Fetch) targetUrls else null,
            )
            activeSubscriptions[spec.id] = record
            val commands = reconcileSubscriptionLocked(spec.id, record)
            val (removed, added) = reconcileActiveRelaysLocked()
            SessionOpenWork(
                commands = commands,
                removedHandles = removed,
                newHandles = added,
                completeImmediately = spec.behavior is SubscriptionBehavior.Fetch && targetUrls.isEmpty(),
            )
        }
        if (!completeImmediately && spec.behavior is SubscriptionBehavior.Fetch) {
            session.timeoutJob = scope.launch {
                delay(spec.behavior.timeoutMillis)
                completeFetch(spec.id, timedOut = true)
            }
        }
        newHandles.forEach { it.relay.connect(scope) }
        sendSubscriptionCommands(commands, removedHandles)
        closeRemovedRelayHandles(removedHandles)
        if (completeImmediately) completeFetch(spec.id, timedOut = false)
        return session
    }

    internal suspend fun updateSubscriptionSession(
        session: SubscriptionSessionImpl,
        filters: List<NostrFilter>,
        target: RelayTarget,
    ) {
        require(filters.isNotEmpty()) { "購読には1件以上のフィルターが必要です" }
        val (commands, removedHandles, newHandles) = stateMutex.withLock {
            val record = activeSubscriptions[session.id]
                ?: error("購読はすでに終了しています: ${session.id}")
            check(record.session === session) { "購読セッションが一致しません: ${session.id}" }
            check(record.behavior is SubscriptionBehavior.Live) { "有限取得セッションは更新できません" }
            record.filters = filters
            record.target = target
            val commands = reconcileSubscriptionLocked(session.id, record)
            val (removed, added) = reconcileActiveRelaysLocked()
            Triple(commands, removed, added)
        }
        newHandles.forEach { it.relay.connect(scope) }
        sendSubscriptionCommands(commands, removedHandles)
        closeRemovedRelayHandles(removedHandles)
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
        val existingHandle = stateMutex.withLock {
            temporarySubscriptions[subscriptionId] = Pair(filter, relayUrl)
            temporaryRelays[relayUrl]
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
        if (!createdRelay) {
            handle.relay.send(buildReqMessage(subscriptionId, filter))
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
                val relayUrl = temporarySubscriptions.remove(subscriptionId)?.second
                if (relayUrl != null) {
                    val target = listOfNotNull(temporaryRelays[relayUrl]?.relay)
                    val closeHandle = if (temporarySubscriptions.values.none { it.second == relayUrl }) {
                        temporaryRelays.remove(relayUrl)
                    } else {
                        null
                    }
                    target to listOfNotNull(closeHandle)
                } else {
                    val target = temporaryRelays.values.map { it.relay }
                    val closeHandles = temporaryRelays.values.toList()
                    temporaryRelays.clear()
                    target to closeHandles
                }
            }
            targets.forEach { it.send(msg) }
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
            val commands = record?.relayStates
                .orEmpty()
                .filterValues { it.sentFilters != null }
                .keys
                .map { relayUrl -> SubscriptionWireCommand(relayUrl, closeMessage) }
            val (removed, _) = reconcileActiveRelaysLocked()
            Triple(commands, removed, record?.session)
        }
        session?.timeoutJob?.cancel()
        sendSubscriptionCommands(commands, removedHandles)
        closeRemovedRelayHandles(removedHandles)
        session?.finish()
    }

    /** 現在接続中のリレー数 */
    val relayCount: Int get() = activeRelayCount.value

    suspend fun targetRelayUrls(target: RelayTarget): Set<String> =
        stateMutex.withLock { target.urls(enabledRelayUrls).toSet() }

    /** 署名済みイベントを有効な全リレーに送信する。 */
    suspend fun publish(event: NostrEvent): RelayPublishResult {
        val targets = targetRelayUrls(RelayTarget.AllEnabled)
        check(targets.isNotEmpty()) { "有効なリレーがありません" }
        val result = publishToRelaysWithResult(event, targets)
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
    ): RelayPublishResult = coroutineScope {
        val targets = relayUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) {
            return@coroutineScope RelayPublishResult(emptySet(), emptyMap())
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
                                check(response.accepted) { response.message.ifBlank { "リレーに拒否されました" } }
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
                                    check(response.accepted) { response.message.ifBlank { "リレーに拒否されました" } }
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
            succeededRelays = results.filter { it.second.isSuccess }.mapTo(linkedSetOf()) { it.first },
            failedRelays = results.mapNotNull { (url, result) ->
                result.exceptionOrNull()?.let { error ->
                    url to (error.message ?: "送信に失敗しました")
                }
            }.toMap(),
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
    ): RelayPublishResult {
        val targets = relayUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return RelayPublishResult(emptySet(), emptyMap())

        val completion = CompletableDeferred<RelayPublishResult>()
        val resultMutex = Mutex()
        val failures = linkedMapOf<String, String>()
        var completedCount = 0

        targets.forEach { relayUrl ->
            scope.launch {
                val result = publishToRelaysWithResult(event, listOf(relayUrl))
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
    private const val MAX_TRANSIENT_RETRIES = 3
    private const val MAX_STRUCTURAL_REFUSALS = 3
}

private data class RelayEnvelope(
    val relayUrl: String,
    val message: RelayMessage,
)

internal class SubscriptionSessionImpl(
    override val id: String,
) : SubscriptionSession {
    private val channel = Channel<SubscriptionSignal>(capacity = 512)
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

private data class ActiveRelayHandle(
    val relay: NostrRelay,
    val collectorJobs: List<kotlinx.coroutines.Job>,
) {
    fun close() {
        relay.disconnect()
        collectorJobs.forEach { it.cancel() }
    }
}
