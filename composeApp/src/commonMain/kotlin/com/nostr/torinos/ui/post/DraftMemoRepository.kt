package com.nostr.torinos.ui.post

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.ui.timeline.SignedEventPublisher
import com.nostr.torinos.ui.timeline.SignedPublishResult
import kotlin.random.Random

/** 自己暗号化した下書き（kind 31234）1件。 */
data class DraftMemo(
    val eventId: String,
    val pubkey: String,
    val memo: PostMemoData,
    val createdAt: Long,
) {
    val displayTime: Long get() = memo.updatedAt.takeIf { it > 0 } ?: createdAt
}

/** 下書きの取得・復号・削除。期間の制限なく、指定リレーの下書きをすべて取る。 */
internal class DraftMemoRepository(
    private val signer: AccountSigner?,
    private val openSession: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val publisher: SignedEventPublisher = SignedEventPublisher(signer),
) {
    /** 秘密鍵がなければ null。 */
    suspend fun loadAll(relayUrl: String?): List<DraftMemo>? {
        val signer = signer ?: return null
        val session = openSession(
            SubscriptionSpec(
                id = "drafts-${Random.nextLong().toULong()}",
                filters = listOf(
                    NostrFilter(
                        kinds = listOf(MEMO_EVENT_KIND),
                        authors = listOf(signer.pubkey),
                        limit = DRAFT_LIMIT,
                    ),
                ),
                target = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled,
                behavior = SubscriptionBehavior.Fetch(MEMO_FETCH_TIMEOUT_MS),
            ),
        )
        val events = linkedMapOf<String, NostrEvent>()
        try {
            session.signals.collect { signal ->
                if (signal is SubscriptionSignal.Event) events[signal.event.id] = signal.event
            }
        } finally {
            runCatching { session.close() }
        }
        val drafts = events.values.mapNotNull { event -> decode(event, signer) }
        return mergeDraftMemos(emptyList(), drafts)
    }

    suspend fun delete(draft: DraftMemo): SignedPublishResult =
        publisher.publish("", 5, draftDeletionTags(draft))

    private fun decode(event: NostrEvent, signer: AccountSigner): DraftMemo? {
        if (event.kind != MEMO_EVENT_KIND || event.pubkey != signer.pubkey) return null
        val payload = runCatching {
            memoJson.decodeFromString<PostMemoPayload>(signer.decrypt(event.content, signer.pubkey))
        }.getOrNull() ?: return null
        return DraftMemo(
            eventId = event.id,
            pubkey = event.pubkey,
            memo = payload.toPostMemoData(
                identifier = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1),
                sourceEventId = event.id,
                sourcePubkey = event.pubkey,
            ),
            createdAt = event.createdAt,
        )
    }
}

/** 同じ下書き（公開鍵と`d`タグ）は最新版だけ残す。同時刻ならイベントIDの小さい方。表示は新しい順。 */
internal fun mergeDraftMemos(
    current: List<DraftMemo>,
    additions: List<DraftMemo>,
): List<DraftMemo> {
    val latestByAddress = linkedMapOf<String, DraftMemo>()
    (current + additions).forEach { item ->
        val address = item.memo.identifier
            ?.let { identifier -> "${item.pubkey}:$identifier" }
            ?: "event:${item.eventId}"
        val existing = latestByAddress[address]
        val shouldReplace = existing == null ||
            item.createdAt > existing.createdAt ||
            (item.createdAt == existing.createdAt && item.eventId < existing.eventId)
        if (shouldReplace) {
            latestByAddress[address] = item
        }
    }
    return latestByAddress.values.sortedByDescending { it.displayTime }
}

internal fun draftDeletionTags(draft: DraftMemo): List<List<String>> = buildList {
    add(listOf("e", draft.eventId))
    draft.memo.identifier?.let { add(listOf("a", "$MEMO_EVENT_KIND:${draft.pubkey}:$it")) }
    add(listOf("k", MEMO_EVENT_KIND.toString()))
    add(listOf("client", "ToriNos"))
}

private const val DRAFT_LIMIT = 5_000
