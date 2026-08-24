package com.nostr.torinos.ui.timeline

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.CancellationException

internal sealed interface SignedPublishResult {
    data class Published(val event: NostrEvent) : SignedPublishResult
    data object MissingSigner : SignedPublishResult
    data class Failed(val cause: Throwable) : SignedPublishResult
}

/** 署名とリレー送信をUI状態から分離する共通コマンドサービス。 */
internal class SignedEventPublisher(
    private val signer: AccountSigner?,
    private val publisher: suspend (NostrEvent) -> RelayPublishResult = NostrRepository::publish,
) {
    val signerPubkey: String? get() = signer?.pubkey

    suspend fun publish(
        content: String,
        kind: Int,
        tags: List<List<String>>,
    ): SignedPublishResult {
        val activeSigner = signer ?: return SignedPublishResult.MissingSigner
        return try {
            val event = activeSigner.sign(content = content, kind = kind, tags = tags)
            val result = publisher(event)
            check(result.succeededRelays.isNotEmpty()) { "すべてのリレーへの送信に失敗しました" }
            SignedPublishResult.Published(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SignedPublishResult.Failed(error)
        }
    }
}
