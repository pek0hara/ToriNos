package com.nostr.torinos.ui.timeline

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.engagement.EngagementAction
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementReducer
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.NoteEngagementCommand
import com.nostr.torinos.engagement.NoteEngagementService
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.model.NostrEvent

/** 楽観更新と署名送信の共通境界。画面Stateそのものは所有しない。 */
internal class NoteEngagementCoordinator(signer: AccountSigner?) {
    private val service = NoteEngagementService(signer)

    fun begin(
        current: NoteEngagementState,
        operationId: EngagementOperationId,
        request: EngagementRequest,
    ): NoteEngagementState = EngagementReducer.reduce(current, EngagementAction.Begin(operationId, request))

    suspend fun execute(
        command: NoteEngagementCommand,
        onSigned: (NostrEvent) -> Unit = {},
    ): Result<NostrEvent> = service.execute(command, onSigned)

    fun commit(
        current: NoteEngagementState,
        operationId: EngagementOperationId,
        publishedEventId: String,
    ): NoteEngagementState = EngagementReducer.reduce(
        current,
        EngagementAction.Commit(operationId, publishedEventId),
    )

    fun rollback(
        current: NoteEngagementState,
        operationId: EngagementOperationId,
    ): NoteEngagementState = EngagementReducer.reduce(current, EngagementAction.Rollback(operationId))
}
