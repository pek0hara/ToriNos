package com.nostr.torinos.ui.search

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.util.networkTraceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/** ページを正常に完了できなかった理由。どれも部分結果を残し、同じ要求で再試行できる。 */
internal sealed interface PageFailure {
    data object Timeout : PageFailure
    data class Closed(val reason: String) : PageFailure
    data class Unavailable(val reason: String) : PageFailure

    /** 要求件数を大きく超えて送られ続けたため、EOSE前に閉じた。 */
    data object Overflow : PageFailure
    data class Error(val message: String?) : PageFailure
}

internal sealed interface PageFetchEnd {
    data object Eose : PageFetchEnd
    data class Failed(val failure: PageFailure) : PageFetchEnd
}

/** 受信したイベントを受け付け続けるか、ページを打ち切るか。 */
internal enum class PageEventDecision { Continue, Stop }

/**
 * 検索リレーへ1ページ分の有限取得を行う。イベントと終了通知を1本の [SubscriptionSession.signals] で
 * 順に処理するため、終了を返した時点でそれ以前に届いたイベントはすべて [onEvent] へ渡し終えている。
 */
internal class PostPageFetcher(
    private val gateway: SearchGateway,
    private val relayUrl: String,
    private val timeoutMillis: Long,
) {
    suspend fun fetch(
        subscriptionId: String,
        filter: NostrFilter,
        onEvent: (NostrEvent) -> PageEventDecision,
    ): PageFetchEnd {
        networkTraceLog { "[Search] page subId=$subscriptionId filter=$filter" }
        val session = try {
            gateway.openSubscription(
                SubscriptionSpec(
                    id = subscriptionId,
                    filters = listOf(filter),
                    target = RelayTarget.Explicit(setOf(relayUrl)),
                    behavior = SubscriptionBehavior.Fetch(timeoutMillis),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return PageFetchEnd.Failed(PageFailure.Error(error.message))
        }
        return try {
            collect(session, onEvent)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PageFetchEnd.Failed(PageFailure.Error(error.message))
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    private suspend fun collect(
        session: SubscriptionSession,
        onEvent: (NostrEvent) -> PageEventDecision,
    ): PageFetchEnd {
        var end: PageFetchEnd? = null
        session.signals.firstOrNull { signal ->
            when (signal) {
                is SubscriptionSignal.Event -> if (onEvent(signal.event) == PageEventDecision.Stop) {
                    end = PageFetchEnd.Failed(PageFailure.Overflow)
                }
                is SubscriptionSignal.FetchCompleted -> end = signal.toPageEnd()
                else -> Unit
            }
            end != null
        }
        return end ?: PageFetchEnd.Failed(PageFailure.Closed("購読が終了しました"))
    }
}

internal fun SubscriptionSignal.FetchCompleted.toPageEnd(): PageFetchEnd {
    val failure = outcomes.values.firstNotNullOfOrNull { outcome ->
        when (outcome) {
            RelayOutcome.Eose -> null
            RelayOutcome.TimedOut -> PageFailure.Timeout
            is RelayOutcome.Closed -> PageFailure.Closed(outcome.reason)
            is RelayOutcome.Unavailable -> PageFailure.Unavailable(outcome.reason)
        }
    }
    return when {
        failure != null -> PageFetchEnd.Failed(failure)
        // 対象リレーが空のまま完了した場合も、検索できていないので成功にしない。
        outcomes.isEmpty() -> PageFetchEnd.Failed(PageFailure.Unavailable("検索リレーに接続できません"))
        timedOut -> PageFetchEnd.Failed(PageFailure.Timeout)
        else -> PageFetchEnd.Eose
    }
}
