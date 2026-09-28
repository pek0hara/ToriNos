package com.nostr.torinos.status

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.CancellationException

data class PublishStatusCommand(
    val identifier: String,
    val content: String,
    val expiration: Long?,
    val referenceUrl: String?,
    val isDeletion: Boolean = false,
)

sealed interface StatusPublishTarget {
    data class SelectedRelay(val relayUrl: String) : StatusPublishTarget
    data object WritableRelays : StatusPublishTarget
}

sealed interface StatusPublishResult {
    data class Published(val event: NostrEvent) : StatusPublishResult
    data class Rejected(val message: String) : StatusPublishResult
}

sealed interface StatusPublishState {
    data object Idle : StatusPublishState
    data object Publishing : StatusPublishState
    data class Succeeded(val eventId: String) : StatusPublishState
    data class Failed(val message: String) : StatusPublishState
}

class StatusPublisher internal constructor(
    private val signer: AccountSigner?,
    private val customEmojis: () -> List<CustomEmoji> = { emptyList() },
    private val send: suspend (NostrEvent, StatusPublishTarget) -> Unit = { event, target ->
        when (target) {
            is StatusPublishTarget.SelectedRelay ->
                NostrRepository.publishToRelays(event, listOf(target.relayUrl))
            // プロフィール画面では全書き込みリレーを対象にするが、未接続リレーの
            // タイムアウトまで編集シートを送信中にしない。最初の成功後も残りは送信される。
            StatusPublishTarget.WritableRelays -> NostrRepository.publishUntilFirstSuccess(event)
        }
    },
) {
    suspend fun publish(command: PublishStatusCommand, target: StatusPublishTarget): StatusPublishResult {
        val activeSigner = signer ?: return StatusPublishResult.Rejected("秘密鍵が見つかりません")
        val identifier = command.identifier.trim().ifBlank { GENERAL_STATUS_IDENTIFIER }
        val content = command.content.trim()
        if (!command.isDeletion && content.isEmpty()) {
            return StatusPublishResult.Rejected("ステータスを入力してください")
        }
        return try {
            val event = activeSigner.sign(
                content = content,
                kind = STATUS_EVENT_KIND,
                tags = StatusEventCodec.buildTags(
                    identifier = identifier,
                    content = content,
                    expiration = command.expiration,
                    explicitReferenceUrl = command.referenceUrl,
                    customEmojis = customEmojis(),
                ),
            )
            send(event, target)
            StatusPublishResult.Published(event)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StatusPublishResult.Rejected(error.message ?: "ステータスの投稿に失敗しました")
        }
    }
}
