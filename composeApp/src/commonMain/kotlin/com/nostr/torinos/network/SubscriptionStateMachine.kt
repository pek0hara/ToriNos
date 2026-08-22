package com.nostr.torinos.network

import com.nostr.torinos.model.NostrFilter

internal enum class RelaySubscriptionPhase {
    Idle,
    Sent,
    QueryingPast,
    Live,
    Closing,
    Closed,
    Suppressed,
}

internal data class RelaySubscriptionState(
    val phase: RelaySubscriptionPhase = RelaySubscriptionPhase.Idle,
    val sentFilters: List<NostrFilter>? = null,
    val connectionGeneration: Long = 0L,
    val refusedFilters: List<NostrFilter>? = null,
    val refusalCount: Int = 0,
)

internal sealed interface SubscriptionCommandDecision {
    data class Open(val filters: List<NostrFilter>) : SubscriptionCommandDecision
    data object Close : SubscriptionCommandDecision
}

internal data class SubscriptionReconcileResult(
    val state: RelaySubscriptionState?,
    val command: SubscriptionCommandDecision?,
)

internal data class ClosedClassification(
    val disposition: RetryDisposition,
    val structural: Boolean,
)

internal fun classifyClosedReason(reason: String): ClosedClassification {
    val prefix = reason.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
    return when (prefix) {
        "auth-required" -> ClosedClassification(RetryDisposition.RetryAfterAuth, structural = false)
        "rate-limited" -> ClosedClassification(RetryDisposition.RetryWithBackoff, structural = false)
        "restricted", "unsupported" -> ClosedClassification(RetryDisposition.RetryOnFilterChange, structural = true)
        "invalid", "blocked", "pow" -> ClosedClassification(RetryDisposition.DoNotRetry, structural = true)
        else -> ClosedClassification(RetryDisposition.RetryWithBackoff, structural = false)
    }
}

internal object SubscriptionStateMachine {
    fun reconcile(
        state: RelaySubscriptionState?,
        desiredFilters: List<NostrFilter>?,
        connectionGeneration: Long,
    ): SubscriptionReconcileResult {
        if (desiredFilters.isNullOrEmpty()) {
            return if (state?.sentFilters != null) {
                SubscriptionReconcileResult(
                    state = state.copy(
                        phase = RelaySubscriptionPhase.Closing,
                        sentFilters = null,
                        connectionGeneration = connectionGeneration,
                    ),
                    command = SubscriptionCommandDecision.Close,
                )
            } else {
                SubscriptionReconcileResult(state = null, command = null)
            }
        }

        val current = state ?: RelaySubscriptionState(connectionGeneration = connectionGeneration)
        if (
            current.phase == RelaySubscriptionPhase.Suppressed &&
            current.refusedFilters == desiredFilters
        ) {
            return SubscriptionReconcileResult(current, null)
        }

        if (current.sentFilters == desiredFilters) {
            return SubscriptionReconcileResult(current, null)
        }

        if (
            current.sentFilters != null &&
            (current.phase == RelaySubscriptionPhase.Sent ||
                current.phase == RelaySubscriptionPhase.QueryingPast)
        ) {
            // 同じsubIdの処理中REQへ新しいREQを重ねず、desired側だけを更新する。
            return SubscriptionReconcileResult(current, null)
        }

        val filterChanged = current.refusedFilters != null && current.refusedFilters != desiredFilters
        val next = current.copy(
            phase = RelaySubscriptionPhase.Sent,
            sentFilters = desiredFilters,
            connectionGeneration = connectionGeneration,
            refusedFilters = if (filterChanged) null else current.refusedFilters,
            refusalCount = if (filterChanged) 0 else current.refusalCount,
        )
        return SubscriptionReconcileResult(next, SubscriptionCommandDecision.Open(desiredFilters))
    }

    fun onEvent(state: RelaySubscriptionState): RelaySubscriptionState =
        if (state.phase == RelaySubscriptionPhase.Sent) {
            state.copy(phase = RelaySubscriptionPhase.QueryingPast)
        } else {
            state
        }

    fun onEose(state: RelaySubscriptionState): RelaySubscriptionState =
        state.copy(
            phase = RelaySubscriptionPhase.Live,
            refusedFilters = null,
            refusalCount = 0,
        )

    fun onDisconnected(
        state: RelaySubscriptionState,
        nextConnectionGeneration: Long,
    ): RelaySubscriptionState = state.copy(
        phase = RelaySubscriptionPhase.Idle,
        sentFilters = null,
        connectionGeneration = nextConnectionGeneration,
    )

    fun onClosed(
        state: RelaySubscriptionState,
        structural: Boolean,
        maxStructuralRefusals: Int,
    ): RelaySubscriptionState {
        val sameRefusal = state.refusedFilters == state.sentFilters
        val count = if (sameRefusal) state.refusalCount + 1 else 1
        return state.copy(
            phase = if (structural && count >= maxStructuralRefusals) {
                RelaySubscriptionPhase.Suppressed
            } else {
                RelaySubscriptionPhase.Closed
            },
            refusedFilters = state.sentFilters,
            refusalCount = count,
        )
    }

    fun prepareRetry(state: RelaySubscriptionState): RelaySubscriptionState = state.copy(
        phase = RelaySubscriptionPhase.Idle,
        sentFilters = null,
    )
}
