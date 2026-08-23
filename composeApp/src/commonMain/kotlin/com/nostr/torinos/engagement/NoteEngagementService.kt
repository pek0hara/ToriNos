package com.nostr.torinos.engagement

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.eventTags
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class NoteEngagementService(
    private val signer: AccountSigner?,
    private val publisher: suspend (NostrEvent) -> RelayPublishResult = NostrRepository::publish,
) {
    suspend fun execute(
        command: NoteEngagementCommand,
        onSigned: (NostrEvent) -> Unit = {},
    ): Result<NostrEvent> {
        val activeSigner = signer
            ?: return Result.failure(IllegalStateException("秘密鍵が設定されていません"))
        return try {
            val event = when (command) {
                is NoteEngagementCommand.AddLike -> activeSigner.sign(
                    content = "+",
                    kind = 7,
                    tags = listOf(
                        listOf("e", command.target.validEventId()),
                        listOf("p", command.target.validEventPubkey()),
                    ),
                )
                is NoteEngagementCommand.AddEmoji -> activeSigner.sign(
                    content = command.option.eventContent,
                    kind = 7,
                    tags = command.option.eventTags(
                        command.target.validEventId(),
                        command.target.validEventPubkey(),
                    ),
                )
                is NoteEngagementCommand.RemoveReaction -> activeSigner.sign(
                    content = "",
                    kind = 5,
                    tags = listOf(listOf("e", command.reactionEventId.requireNotBlank("reactionEventId"))),
                )
                is NoteEngagementCommand.AddRepost -> activeSigner.sign(
                    content = Json.encodeToString(NostrEvent.serializer(), command.event),
                    kind = 6,
                    tags = listOf(
                        listOf("e", command.event.id.requireNotBlank("event.id")),
                        listOf("p", command.event.pubkey.requireNotBlank("event.pubkey")),
                    ),
                )
                is NoteEngagementCommand.RemoveRepost -> activeSigner.sign(
                    content = "",
                    kind = 5,
                    tags = listOf(listOf("e", command.repostEventId.requireNotBlank("repostEventId"))),
                )
            }
            onSigned(event)
            val publishResult = publisher(event)
            check(publishResult.succeededRelays.isNotEmpty()) {
                "すべてのリレーへの送信に失敗しました"
            }
            Result.success(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}

sealed interface NoteEngagementCommand {
    data class AddLike(val target: NoteTarget) : NoteEngagementCommand
    data class AddEmoji(val target: NoteTarget, val option: ReactionOption) : NoteEngagementCommand
    data class RemoveReaction(val reactionEventId: String) : NoteEngagementCommand
    data class AddRepost(val event: NostrEvent) : NoteEngagementCommand
    data class RemoveRepost(val repostEventId: String) : NoteEngagementCommand
}

data class NoteTarget(
    val eventId: String,
    val eventPubkey: String,
) {
    internal fun validEventId(): String = eventId.requireNotBlank("eventId")
    internal fun validEventPubkey(): String = eventPubkey.requireNotBlank("eventPubkey")
}

private fun String.requireNotBlank(name: String): String =
    also { require(it.isNotBlank()) { "$name must not be blank" } }
