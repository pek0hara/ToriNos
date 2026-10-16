package com.nostr.torinos.ui.post

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.ui.timeline.SignedEventPublisher
import com.nostr.torinos.ui.timeline.SignedPublishResult
import kotlinx.coroutines.flow.transformWhile
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

/** 1つのリレーから読んだ下書き。[complete] はそのリレーが EOSE まで答えたときだけ true。 */
internal data class DraftMemoLoad(val drafts: List<DraftMemo>, val complete: Boolean)

/** 下書きの取得・復号・削除。期間の制限なく、指定リレーの下書きをすべて取る。 */
internal class DraftMemoRepository(
    private val signer: AccountSigner?,
    private val openSession: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val publisher: SignedEventPublisher = SignedEventPublisher(signer),
) {
    /** 秘密鍵がなければ null。 */
    suspend fun loadAll(relayUrl: String?): DraftMemoLoad? {
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
        var complete = false
        try {
            // 完了の知らせで受信を終える。流れが閉じるのを待つと、閉じなかったときに読み込み中のまま残る。
            session.signals.transformWhile { signal -> emit(signal); signal !is SubscriptionSignal.FetchCompleted }
                .collect { signal ->
                    when (signal) {
                        is SubscriptionSignal.Event -> events[signal.event.id] = signal.event
                        is SubscriptionSignal.FetchCompleted -> complete = !signal.timedOut &&
                            signal.outcomes.isNotEmpty() && signal.outcomes.values.all { it == RelayOutcome.Eose }
                        else -> Unit
                    }
                }
        } finally {
            runCatching { session.close() }
        }
        // 削除版や読み込めない最新版も勝者とし、古い版を復活させない。
        val latest = latestPostDraftEvents(events.values.filter { it.kind == MEMO_EVENT_KIND && it.pubkey == signer.pubkey })
        val drafts = latest.mapNotNull { event -> decode(event, signer) }
        return DraftMemoLoad(mergeDraftMemos(emptyList(), drafts), complete)
    }

    suspend fun delete(draft: DraftMemo): SignedPublishResult =
        publisher.publish("", 5, draftDeletionTags(draft))

    private fun decode(event: NostrEvent, signer: AccountSigner): DraftMemo? {
        if (event.kind != MEMO_EVENT_KIND || event.pubkey != signer.pubkey) return null
        if (event.tags.any { it == listOf("k", "30023") }) return null
        val payload = runCatching {
            decodePostDraft(signer.decrypt(event.content, signer.pubkey), event)
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

/** 空contentや復号不能な最新版も含めて選ぶ。復号はこの後に行う。 */
internal fun latestPostDraftEvents(events: Collection<NostrEvent>): List<NostrEvent> =
    events.groupBy { event -> Triple(event.pubkey, event.kind,
        event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: "event:${event.id}") }
        .values.map { versions -> versions.sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id }).first() }
