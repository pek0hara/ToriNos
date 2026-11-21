package com.nostr.torinos.ui.timeline

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.engagement.EngagementAction
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementReducer
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.NoteEngagementCommand
import com.nostr.torinos.engagement.NoteEngagementService
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.RepostNotice
import com.nostr.torinos.engagement.RepostNotices
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayPublishResult

/**
 * 楽観更新と署名送信の共通境界。画面Stateそのものは所有しない。
 * 送信は最初の1リレーへ届いた時点で完了扱いにし、残りのリレーへはリポジトリ側で送り続ける。
 * 全リレーを待つと、接続できないリレーの時間切れ（10秒）まで確定せず、その間は取り消しもできない。
 * リポストは全リレーの結果がそろったら [onRepostDelivered] で知らせる（投稿と同じ完了表示を出すため）。
 */
internal class NoteEngagementCoordinator(
    signer: AccountSigner?,
    publisher: suspend (NostrEvent, onAllCompleted: suspend (RelayPublishResult) -> Unit) -> RelayPublishResult =
        NostrRepository::publishUntilFirstSuccess,
    relayHint: suspend (NostrEvent) -> String? = { NostrRepository.resolveEventRelay(it.id) },
    onRepostDelivered: (RepostNotice) -> Unit = RepostNotices::report,
) {
    private val service = NoteEngagementService(signer, relayHint = relayHint) { event ->
        publisher(event) { all ->
            // どのリレーにも届かなかったときは送信失敗のメッセージに任せる。
            if (event.isRepost() && all.succeededRelays.isNotEmpty()) {
                onRepostDelivered(RepostNotice(event.pubkey, all))
            }
        }
    }

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

private fun NostrEvent.isRepost(): Boolean = kind == 6 || kind == 16
