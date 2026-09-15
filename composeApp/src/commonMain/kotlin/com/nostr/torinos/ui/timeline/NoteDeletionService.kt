package com.nostr.torinos.ui.timeline

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal data class NoteDeletion(
    val sessionId: String?,
    val eventId: String,
)

/** 別画面で削除した投稿を、開いたままのタイムラインからも取り除く。 */
internal object NoteDeletionSync {
    private val mutableUpdates = MutableSharedFlow<NoteDeletion>(extraBufferCapacity = 32)
    val updates: SharedFlow<NoteDeletion> = mutableUpdates.asSharedFlow()

    fun publish(deletion: NoteDeletion) {
        mutableUpdates.tryEmit(deletion)
    }
}

internal sealed interface NoteDeletionResult {
    data object Deleted : NoteDeletionResult
    data object MissingSigner : NoteDeletionResult
    data object NotOwner : NoteDeletionResult
    data class Failed(val cause: Throwable) : NoteDeletionResult
}

/** 通常投稿の所有者確認、NIP-09削除イベントの生成・送信、画面間同期をまとめる。 */
internal class NoteDeletionService(
    signer: AccountSigner?,
    private val sessionId: String?,
    private val publisher: SignedEventPublisher = SignedEventPublisher(signer),
) {
    suspend fun delete(event: NostrEvent): NoteDeletionResult {
        val signerPubkey = publisher.signerPubkey ?: return NoteDeletionResult.MissingSigner
        if (event.pubkey != signerPubkey) return NoteDeletionResult.NotOwner

        return when (
            val result = publisher.publish(
                content = "",
                kind = 5,
                tags = listOf(
                    listOf("e", event.id),
                    listOf("k", event.kind.toString()),
                    listOf("client", "ToriNos"),
                ),
            )
        ) {
            is SignedPublishResult.Published -> {
                NoteDeletionSync.publish(NoteDeletion(sessionId, event.id))
                NoteDeletionResult.Deleted
            }
            SignedPublishResult.MissingSigner -> NoteDeletionResult.MissingSigner
            is SignedPublishResult.Failed -> NoteDeletionResult.Failed(result.cause)
        }
    }
}
