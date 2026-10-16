package com.nostr.torinos.network

/**
 * 有限取得での、1つのリレーの結果の扱い。フィード・リアクション数・ジャーナルで同じ規則にする。
 * 接続できないリレーを待たない処理は[fetchFailureUpdate]が行い、ここでは届いた結果を分けるだけにする。
 */
internal sealed interface RelayFetchVerdict {
    /** EOSE を返した。このリレーの分は取り終えた。 */
    data object Succeeded : RelayFetchVerdict

    /** 接続できない・時間切れ・再試行できる CLOSED。間を空けて取り直す。 */
    data object RetryWithBackoff : RelayFetchVerdict

    /** 複合のフィルターを拒否された。フィルターを分ければ通る可能性がある。 */
    data object IsolateFilters : RelayFetchVerdict

    /** 認証必須・ブロックなど。送り直しても同じ結果になる。 */
    data object Stop : RelayFetchVerdict
}

/** 結果がない（取得前に止めた）リレーは、時間切れと同じく取り直す。 */
internal fun RelayOutcome?.verdict(): RelayFetchVerdict = when (this) {
    RelayOutcome.Eose -> RelayFetchVerdict.Succeeded
    is RelayOutcome.Closed -> retry.verdict()
    is RelayOutcome.Unavailable, RelayOutcome.TimedOut, null -> RelayFetchVerdict.RetryWithBackoff
}

/** 取り終えなかったリレーの扱い。CLOSED を受けていなければ（null）時間切れなどと同じく取り直す。 */
internal fun RetryDisposition?.verdict(): RelayFetchVerdict = when (this) {
    null, RetryDisposition.RetryWithBackoff -> RelayFetchVerdict.RetryWithBackoff
    RetryDisposition.RetryOnFilterChange -> RelayFetchVerdict.IsolateFilters
    RetryDisposition.RetryAfterAuth, RetryDisposition.DoNotRetry -> RelayFetchVerdict.Stop
}

/** 1回の有限取得の結果をリレーごとに分ける。 */
internal class RelayFetchSummary(val outcomes: Map<String, RelayOutcome>) {
    val succeeded: Set<String> = outcomes.filterValues { it.verdict() == RelayFetchVerdict.Succeeded }.keys

    /** 取り終えなかったリレーと、その扱い。 */
    val failed: Map<String, RelayFetchVerdict> =
        outcomes.mapValues { (_, outcome) -> outcome.verdict() }.filterValues { it != RelayFetchVerdict.Succeeded }

    /** 間を空けて取り直すリレー。 */
    val retryable: Set<String> = failed.filterValues { it == RelayFetchVerdict.RetryWithBackoff }.keys

    /** どれかのリレーが取り終えた。取れた分を確定してよい。 */
    val anySucceeded: Boolean get() = succeeded.isNotEmpty()

    /** すべてのリレーが取り終えた。取得結果で既存を置き換えてよい。 */
    val allSucceeded: Boolean get() = outcomes.isNotEmpty() && failed.isEmpty()
}

/**
 * 失敗したリレーを取り直すまでの待ち時間。[baseDelayMillis]から倍にしていき、[maxDelayMillis]で止める。
 * 続けて[maxFailures]回失敗したら諦める。
 */
internal data class RelayRetryPolicy(
    val baseDelayMillis: Long,
    val maxDelayMillis: Long,
    val maxFailures: Int = Int.MAX_VALUE,
) {
    /** 続けて[failureCount]回失敗した後の待ち時間。諦めるなら null。 */
    fun delayAfter(failureCount: Int): Long? {
        if (failureCount >= maxFailures) return null
        val attempt = failureCount.coerceAtLeast(1)
        return (baseDelayMillis * (1L shl (attempt - 1).coerceAtMost(MAX_SHIFT))).coerceAtMost(maxDelayMillis)
    }

    companion object {
        private const val MAX_SHIFT = 4

        /** フィードの履歴取得の待ち時間。諦める回数は呼び出し側が決める。 */
        fun history(maxFailures: Int = Int.MAX_VALUE) = RelayRetryPolicy(2_000L, 30_000L, maxFailures)
    }
}
