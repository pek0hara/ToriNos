package com.nostr.torinos.network

import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.Job

internal interface SessionController {
    suspend fun update(session: SubscriptionSessionImpl, filters: List<NostrFilter>, target: RelayTarget)
    suspend fun close(session: SubscriptionSessionImpl)
    suspend fun finish(session: SubscriptionSessionImpl)
}

internal object RepositorySessionController : SessionController {
    override suspend fun update(session: SubscriptionSessionImpl, filters: List<NostrFilter>, target: RelayTarget) =
        NostrRepository.updateSubscriptionSession(session, filters, target)
    override suspend fun close(session: SubscriptionSessionImpl) = NostrRepository.closeSubscriptionSession(session)
    override suspend fun finish(session: SubscriptionSessionImpl) = NostrRepository.finishPendingFetchRelays(session)
}

internal class SubscriptionSessionImpl(
    override val id: String,
    lossless: Boolean = false,
    private val controller: SessionController = RepositorySessionController,
) : SubscriptionSession {
    private val mailbox = SessionSignalMailbox(lossless)
    private val specLock = SynchronousLock()
    private var currentSpec: SubscriptionSpec? = null
    override val signals = mailbox.signals
    override val deliveryState = mailbox.deliveryState
    override var expectedRelayUrls: Set<String> = emptySet()
        private set
    internal var timeoutJob: Job? = null

    internal fun initializeTargets(urls: Set<String>) { expectedRelayUrls = urls.toSet() }

    internal fun configureSpec(spec: SubscriptionSpec, relays: Set<String>) = specLock.withLock {
        val fixed = spec.copy(target = RelayTarget.Explicit(relays.toSet()))
        val changed = currentSpec != null && currentSpec != fixed
        currentSpec = fixed
        mailbox.configure(relays, changed)
    }

    internal fun liveRecoverySpec(): SubscriptionSpec? = specLock.withLock {
        currentSpec?.takeIf { it.behavior == SubscriptionBehavior.Live }
    }

    internal fun recoveryBarrier() = mailbox.barrier()
    internal suspend fun awaitRecoveryBarrier(barrier: RecoveryBarrier) = mailbox.awaitApplied(barrier)
    internal fun acknowledgeRecovery(barrier: RecoveryBarrier) = mailbox.acknowledge(barrier)
    internal fun reportHandlerFailure(signal: SubscriptionSignal) = mailbox.reportHandlerFailure(signal)

    override suspend fun update(filters: List<NostrFilter>, target: RelayTarget) = controller.update(this, filters, target)
    override suspend fun close() = controller.close(this)
    override suspend fun finishPendingRelays() = controller.finish(this)
    internal fun emit(signal: SubscriptionSignal) { mailbox.emit(signal) }
    internal fun finish() {
        timeoutJob?.cancel()
        timeoutJob = null
        mailbox.close()
    }
}
