package com.nostr.torinos.ui.thread

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.CancellationException

internal data class ReplyCommand(
    val content: String,
    val replyToId: String,
    val replyToPubkey: String,
    val noteContext: NoteContext,
)

internal sealed interface ReplyPublishResult {
    data class Published(val event: NostrEvent) : ReplyPublishResult

    sealed interface Failure : ReplyPublishResult {
        data object EmptyContent : Failure
        data object MissingSigner : Failure
        data class PublishFailed(val cause: Throwable) : Failure
    }
}

internal class ReplyPublisher(
    private val signer: AccountSigner?,
    private val publisher: suspend (NostrEvent) -> RelayPublishResult = NostrRepository::publish,
) {
    suspend fun publish(command: ReplyCommand): ReplyPublishResult {
        val content = command.content.trim()
        if (content.isEmpty()) return ReplyPublishResult.Failure.EmptyContent
        val activeSigner = signer ?: return ReplyPublishResult.Failure.MissingSigner

        return try {
            val tags = command.noteContext.replyTags(command.replyToId, command.replyToPubkey) +
                listOf(listOf("client", "ToriNos"))
            val event = activeSigner.sign(
                content = content,
                kind = command.noteContext.eventKind,
                tags = tags,
            )
            val result = publisher(event)
            check(result.succeededRelays.isNotEmpty()) {
                "すべてのリレーへの送信に失敗しました"
            }
            ReplyPublishResult.Published(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ReplyPublishResult.Failure.PublishFailed(error)
        }
    }
}
