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
    /**
     * 配信中の購読が切断や CLOSED で途切れた時刻（epoch 秒）。再開後の EOSE で購読側へ知らせ、
     * 途切れていた間をリレーの件数上限に左右されず取り直せるようにする。
     */
    val interruptedAt: Long? = null,
    /** 一度でも EOSE を受けて配信状態になった。フィルター更新中の切断も途切れとして扱うため。 */
    val wasLive: Boolean = false,
    /**
     * 途切れた後に送り直した REQ で届いた最古の created_at を、[sentFilters] の添字ごとに持つ。
     * 件数上限はフィルターごとに効くので、投稿と返信などをまとめて最古を取ると切れを見逃す。
     */
    val replayOldestByFilter: Map<Int, Long> = emptyMap(),
)

/** 再送 REQ で取り戻せなかった区間。[interruptedAt] から [replayOldestAt] までを購読側で取り直す。 */
internal data class ReplayGap(
    val interruptedAt: Long,
    val replayOldestAt: Long,
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

    fun onEvent(
        state: RelaySubscriptionState,
        createdAt: Long? = null,
        kind: Int? = null,
    ): RelaySubscriptionState {
        val replaying = state.interruptedAt != null && createdAt != null &&
            (state.phase == RelaySubscriptionPhase.Sent || state.phase == RelaySubscriptionPhase.QueryingPast)
        val next = if (state.phase == RelaySubscriptionPhase.Sent) {
            state.copy(phase = RelaySubscriptionPhase.QueryingPast)
        } else {
            state
        }
        if (!replaying) return next
        val filterIndex = state.sentFilters
            ?.indexOfFirst { filter -> filter.kinds?.let { kind in it } ?: true }
            ?.takeIf { it >= 0 }
            ?: 0
        val oldest = minOf(state.replayOldestByFilter[filterIndex] ?: Long.MAX_VALUE, createdAt!!)
        return next.copy(replayOldestByFilter = state.replayOldestByFilter + (filterIndex to oldest))
    }

    /**
     * 再送 REQ の保存済み分が届き終わった時点で、取り戻せていない区間を返す。
     * あるフィルターの再送が途切れた時刻より前まで届いていれば、そのフィルターは件数上限に
     * 切られていない。届いた最古が途切れた時刻より新しいフィルターがあれば、そこまでを取り直す。
     * 途切れている間にフィルターの since が進んだ場合も、その since までは再送で取れないので含める。
     */
    fun replayGap(state: RelaySubscriptionState): ReplayGap? {
        val interruptedAt = state.interruptedAt ?: return null
        val truncatedUntil = state.replayOldestByFilter.values.filter { it > interruptedAt }.maxOrNull()
        val skippedUntil = state.sentFilters
            ?.mapNotNull { it.since }
            ?.maxOrNull()
            ?.takeIf { it > interruptedAt }
        val recoverUntil = listOfNotNull(truncatedUntil, skippedUntil).maxOrNull() ?: return null
        return ReplayGap(interruptedAt, recoverUntil)
    }

    fun onEose(state: RelaySubscriptionState): RelaySubscriptionState =
        state.copy(
            phase = RelaySubscriptionPhase.Live,
            refusedFilters = null,
            refusalCount = 0,
            interruptedAt = null,
            wasLive = true,
            replayOldestByFilter = emptyMap(),
        )

    /**
     * [disconnectedAt] は接続が切れた時刻。配信中（または再開待ち）だった購読だけ途切れとして記録し、
     * 再接続を繰り返しても最初に途切れた時刻を保つ。
     */
    fun onDisconnected(
        state: RelaySubscriptionState,
        nextConnectionGeneration: Long,
        disconnectedAt: Long? = null,
    ): RelaySubscriptionState = state.copy(
        phase = RelaySubscriptionPhase.Idle,
        sentFilters = null,
        connectionGeneration = nextConnectionGeneration,
        interruptedAt = state.interruptedAt ?: disconnectedAt?.takeIf { state.wasLive },
        replayOldestByFilter = emptyMap(),
    )

    fun onClosed(
        state: RelaySubscriptionState,
        structural: Boolean,
        maxStructuralRefusals: Int,
        closedAt: Long? = null,
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
            interruptedAt = state.interruptedAt ?: closedAt?.takeIf { state.wasLive },
            replayOldestByFilter = emptyMap(),
        )
    }

    /**
     * 配信中の購読が一時的な CLOSED を受けたときの再試行間隔。回数で諦めると以後の投稿を
     * 受け取れなくなるため、[quickAttempts] 回までは間隔を倍にし、その後は [slowDelayMillis] ごとに続ける。
     * 理由不明の CLOSED も一時的と分類されるので、恒久的な拒否でも負荷が小さい間隔に落とす。
     */
    fun liveRetryDelayMillis(
        attempt: Int,
        baseDelayMillis: Long,
        quickAttempts: Int,
        slowDelayMillis: Long,
    ): Long =
        if (attempt <= quickAttempts) {
            baseDelayMillis * (1L shl (attempt - 1).coerceAtLeast(0))
        } else {
            slowDelayMillis
        }

    fun prepareRetry(state: RelaySubscriptionState): RelaySubscriptionState = state.copy(
        phase = RelaySubscriptionPhase.Idle,
        sentFilters = null,
    )
}
