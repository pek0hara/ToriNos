package com.nostr.torinos.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class RelayConnectionFailure(val reason: String, val atMillis: Long)

internal data class RelayHealth(
    /** つながる前に失敗し、まだつながっていない。再試行がまた失敗しても最初の失敗を持ち続ける。 */
    val failure: RelayConnectionFailure? = null,
    /** 接続を試みている最中。 */
    val attempting: Boolean = false,
) {
    /**
     * 失敗して、次の再試行を待っている。再試行の接続中は含めない。
     * 復帰の直後などは前の失敗が残ったまま再接続が始まり、すぐつながることが多いため、その結果を待つ。
     */
    val isUnavailable: Boolean get() = failure != null && !attempting
}

/**
 * 1つのリレー接続が「接続できない」かを判定する。接続そのものは持たない（[NostrRelay] が呼ぶ）。
 * 1回目の接続中は接続できないにしない。数ミリ秒差でつながるリレーを外すと、あとから投稿が割り込むため。
 */
internal class RelayConnectionHealth(private val nowMillis: () -> Long) {
    private val _state = MutableStateFlow(RelayHealth())
    val state: StateFlow<RelayHealth> = _state.asStateFlow()

    fun onAttemptStarted() {
        _state.update { it.copy(attempting = true) }
    }

    fun onConnected() {
        _state.value = RelayHealth()
    }

    fun onFailedBeforeConnecting(error: Throwable) {
        // disconnect() や画面を閉じたときの中断は、リレーの失敗ではない。
        if (error is CancellationException) {
            _state.update { it.copy(attempting = false) }
            return
        }
        _state.update { current ->
            RelayHealth(failure = current.failure ?: RelayConnectionFailure(failureReason(error), nowMillis()))
        }
    }

    fun reset() {
        _state.value = RelayHealth()
    }

    private fun failureReason(error: Throwable): String {
        val name = error::class.simpleName ?: "Throwable"
        val message = error.message?.takeIf { it.isNotBlank() }
        return (if (message == null) name else "$name: $message").take(MAX_REASON_LENGTH)
    }

    private companion object {
        const val MAX_REASON_LENGTH = 120
    }
}

internal data class FetchFailureUpdate(
    /** 新しく [RelayOutcome.Unavailable] にするリレー。 */
    val unavailable: Map<String, RelayOutcome.Unavailable>,
    /** 対象の全リレーに結果がそろった。 */
    val settled: Boolean,
)

/**
 * 有限取得の対象のうち、接続できない（[RelayHealth.isUnavailable]）でまだ結果のないリレーを [RelayOutcome.Unavailable] にする。
 * EOSE や CLOSED をすでに返したリレーは変えない。
 * どのリレーにもつながっていないときは、端末側の通信の問題（オフライン、復帰の直後）とみなして何も変えず、
 * 今までどおり時間切れまで待つ。
 */
internal fun fetchFailureUpdate(
    expected: Set<String>,
    outcomes: Map<String, RelayOutcome>,
    unavailable: Map<String, RelayConnectionFailure>,
    anyRelayConnected: Boolean,
): FetchFailureUpdate {
    val marked = if (!anyRelayConnected) {
        emptyMap()
    } else {
        expected.filter { it !in outcomes }
            .mapNotNull { url -> unavailable[url]?.let { url to RelayOutcome.Unavailable(it.reason) } }
            .toMap()
    }
    return FetchFailureUpdate(
        unavailable = marked,
        settled = expected.all { it in outcomes || it in marked },
    )
}
