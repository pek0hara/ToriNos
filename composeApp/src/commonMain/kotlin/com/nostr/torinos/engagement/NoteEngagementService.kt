package com.nostr.torinos.engagement

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.eventTags
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class NoteEngagementService(
    private val signer: AccountSigner?,
    private val relayHint: suspend (NostrEvent) -> String? = { NostrRepository.resolveEventRelay(it.id) },
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
                    ) + command.target.addressableTags(),
                )
                is NoteEngagementCommand.AddEmoji -> activeSigner.sign(
                    content = command.option.eventContent,
                    kind = 7,
                    tags = command.option.eventTags(
                        command.target.validEventId(),
                        command.target.validEventPubkey(),
                    ) + command.target.addressableTags(),
                )
                is NoteEngagementCommand.RemoveReaction -> activeSigner.sign(
                    content = "",
                    kind = 5,
                    tags = listOf(listOf("e", command.reactionEventId.requireNotBlank("reactionEventId"))),
                )
                is NoteEngagementCommand.AddRepost -> {
                    require(command.event.kind != COMMENT_EVENT_KIND) {
                        "kind 1111の汎用リポストには対応していません"
                    }
                    val hint = relayHint(command.event)?.trim()?.takeIf {
                        it.startsWith("wss://") || it.startsWith("ws://")
                    } ?: error("元の投稿を取得したリレーが不明です。投稿を再取得してからリポストしてください")
                    activeSigner.sign(
                        content = if (command.event.tags.any { it.firstOrNull() == "-" }) "" else
                            Json.encodeToString(NostrEvent.serializer(), command.event),
                        kind = if (command.event.kind == 1) 6 else 16,
                        tags = buildList {
                            add(listOf("e", command.event.id.requireNotBlank("event.id"), hint))
                            add(listOf("p", command.event.pubkey.requireNotBlank("event.pubkey")))
                            if (command.event.kind != 1) add(listOf("k", command.event.kind.toString()))
                            if (command.event.kind == 0 || command.event.kind == 3 || command.event.kind in 10000..19999 ||
                                command.event.kind in 30000..39999) {
                                val identifier = if (command.event.kind in 30000..39999)
                                    command.event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1).orEmpty() else ""
                                add(listOf("a", "${command.event.kind}:${command.event.pubkey}:$identifier", hint))
                            }
                        },
                    )
                }
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

/**
 * リアクション対象。記事などのaddressable eventでは[address]と[kind]を指定し、
 * NIP-25に従って`a`と`k`タグも付ける。
 */
data class NoteTarget(
    val eventId: String,
    val eventPubkey: String,
    val address: String? = null,
    val kind: Int? = null,
) {
    internal fun validEventId(): String = eventId.requireNotBlank("eventId")
    internal fun validEventPubkey(): String = eventPubkey.requireNotBlank("eventPubkey")

    internal fun addressableTags(): List<List<String>> = buildList {
        address?.let { add(listOf("a", it.requireNotBlank("address"))) }
        kind?.let { add(listOf("k", it.toString())) }
    }
}

private fun String.requireNotBlank(name: String): String =
    also { require(it.isNotBlank()) { "$name must not be blank" } }
