package com.nostr.torinos.ui.timeline

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ReactionEventStore
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.CancellationException

internal sealed interface SignedPublishResult {
    /** 1件以上のリレーが受け付けた。[relayResult] は一部失敗を含みうる(第16.9節)。 */
    data class Published(
        val event: NostrEvent,
        val relayResult: RelayPublishResult = RelayPublishResult(emptySet(), emptyMap()),
    ) : SignedPublishResult
    data object MissingSigner : SignedPublishResult
    /** 署名失敗、または全リレーへの送信失敗。後者では [relayResult] にリレー別の理由が入る。 */
    data class Failed(
        val cause: Throwable,
        val relayResult: RelayPublishResult? = null,
    ) : SignedPublishResult
}

/** 送信先ごとの結果を [onRelayResult] で逐次通知し、最初の成功または全件失敗で戻る。 */
internal typealias RelayPublisher = suspend (
    event: NostrEvent,
    relayUrls: Collection<String>,
    onRelayResult: suspend (RelayPublishResult) -> Unit,
) -> RelayPublishResult

/** 署名とリレー送信をUI状態から分離する共通コマンドサービス。 */
internal class SignedEventPublisher(
    private val signer: AccountSigner?,
    private val relayPublisher: RelayPublisher = { event, urls, onRelayResult ->
        // リレー別の成功・失敗を表示するため、送信完了ではなく OK 応答での受理を成功とする。
        // 最初の受理で戻り、残りのリレーの結果は onRelayResult で後から届く。応答しないリレーが
        // 1件あっても投稿ボタンを timeout まで塞がない。
        NostrRepository.publishToRelaysUntilFirstSuccess(
            event = event,
            relayUrls = urls,
            onRelayResult = onRelayResult,
            awaitAcceptance = true,
        )
    },
    // 既存の呼び出し側が末尾ラムダで渡すため最後に置く。
    private val publisher: suspend (NostrEvent) -> RelayPublishResult = NostrRepository::publish,
) {
    val signerPubkey: String? get() = signer?.pubkey

    /**
     * [relayUrls] が null ならユーザーの書き込みリレーへ送る(従来動作)。指定時はその集合へだけ送り、
     * 未接続・未登録のリレーには一時接続する。1件以上成功すれば [SignedPublishResult.Published]。
     * 指定時は最初の受理で戻るため、戻り値の relayResult はその時点までの結果であり、
     * 残りは [onRelayResult] で届く。
     */
    suspend fun publish(
        content: String,
        kind: Int,
        tags: List<List<String>>,
        relayUrls: Collection<String>? = null,
        onEventRelayResult: suspend (NostrEvent, RelayPublishResult) -> Unit = { _, _ -> },
        onRelayResult: suspend (RelayPublishResult) -> Unit = {},
    ): SignedPublishResult {
        val activeSigner = signer ?: return SignedPublishResult.MissingSigner
        val event = try {
            activeSigner.sign(content = content, kind = kind, tags = tags)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return SignedPublishResult.Failed(error)
        }
        if (relayUrls == null) {
            return try {
                val result = publisher(event)
                check(result.succeededRelays.isNotEmpty()) { "すべてのリレーへの送信に失敗しました" }
                SignedPublishResult.Published(event, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SignedPublishResult.Failed(error)
            }
        }
        return try {
            val result = relayPublisher(event, relayUrls) { relayResult ->
                if (relayResult.succeededRelays.isNotEmpty()) {
                    ReactionEventStore.observe(event, relayResult.succeededRelays)
                }
                onRelayResult(relayResult)
                onEventRelayResult(event, relayResult)
            }
            if (result.succeededRelays.isEmpty()) {
                SignedPublishResult.Failed(IllegalStateException("すべてのリレーへの送信に失敗しました"), result)
            } else {
                ReactionEventStore.observe(event, result.succeededRelays)
                SignedPublishResult.Published(event, result)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SignedPublishResult.Failed(error)
        }
    }
}
