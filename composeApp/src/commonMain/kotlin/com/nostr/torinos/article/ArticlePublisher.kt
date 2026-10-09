package com.nostr.torinos.article

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.ui.article.buildArticleTags
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/** Relay-saved draft plus session-only signed attempts. No device persistence. */
internal class ArticlePublisher(
    private val owner: String,
    private val signer: AccountSigner,
    private val store: ArticleDraftStore,
    private val writableRelays: () -> List<String>,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val send: suspend (NostrEvent, Collection<String>) -> RelayPublishResult = { event, relays ->
        NostrRepository.publishToRelaysWithResult(event, relays, awaitAcceptance = true)
    },
) {
    private val lock = Mutex()
    suspend fun publish(draft: ArticleDraft): ArticlePublishAttempt = lock.withLock {
        require(draft.ownerPubkey == owner && signer.pubkey == owner)
        withPublicationLease(draft.articleIdentifier) { lease -> prepareAndPublish(draft, lease) }
    }
    private suspend fun prepareAndPublish(draft: ArticleDraft, lease: ArticlePublishLease): ArticlePublishAttempt {
        val targets = writableRelays().distinct()
        check(targets.isNotEmpty()) { "投稿先リレーを設定してください" }
        validateArticleDraft(draft)?.let { error(it) }
        val data = store.read(owner)
        val identity = data.identities[draft.articleIdentifier]
        val minimum = (identity?.maxCreatedAt ?: 0) + 1
        check(minimum <= nowSeconds() + 5) { "既知の版が端末時刻より先です。時刻を確認してから公開してください" }
        kotlinx.coroutines.withTimeout(6_000) {
            while (nowSeconds() < minimum) kotlinx.coroutines.delay(100)
        }
        val createdAt = nowSeconds()
        val publishedAt = identity?.publishedAt ?: draft.originalPublishedAt ?: createdAt
        val tags = buildArticleTags(draft.articleIdentifier, draft.title, draft.summary, draft.coverImageUrl,
            draft.topicsInput, publishedAt, draft.hasMatometrTag)
        val event = signer.sign(draft.content, NIP23_ARTICLE_KIND, tags, createdAt)
        check(event.pubkey == owner)
        val preparedDraft = draft.copy(originalPublishedAt = publishedAt)
        val attempt = ArticlePublishAttempt(draft = preparedDraft, event = event,
            relays = targets.associateWith { ArticleRelayStatus(ArticleRelayState.Pending) })
        store.transaction(owner) { current ->
            checkLease(current, draft.articleIdentifier, lease)
            if (current.drafts[draft.draftId]?.revision != draft.revision) throw ArticleStoreConflict()
            if (current.identities[draft.articleIdentifier] != identity) throw ArticleStoreConflict()
            current.copy(
                drafts = current.drafts + (draft.draftId to preparedDraft),
                identities = current.identities + (draft.articleIdentifier to ArticleIdentity(draft.articleIdentifier, publishedAt, createdAt)),
                attempts = current.attempts.mapValues { (_, old) ->
                    if (old.draft.articleIdentifier == draft.articleIdentifier && !old.complete && old.supersededBy == null)
                        old.copy(supersededBy = attempt.id) else old
                } + (attempt.id to attempt),
            )
        }
        return transmit(attempt, recovering = false, lease)
    }
    suspend fun retry(attemptId: String): ArticlePublishAttempt = lock.withLock {
        val attempt = store.read(owner).attempts[attemptId] ?: error("公開履歴が見つかりません")
        withPublicationLease(attempt.draft.articleIdentifier) { lease ->
            val current = store.read(owner).attempts[attemptId] ?: error("公開履歴が見つかりません")
            check(current.supersededBy == null) { "修正版が公開されたため、旧版を再送できません" }
            if (current.complete) cleanPublishedDraft(current) else transmit(current, recovering = true, lease)
        }
    }
    private suspend fun withPublicationLease(identifier: String, action: suspend (ArticlePublishLease) -> ArticlePublishAttempt): ArticlePublishAttempt {
        val holder = articleLocalId()
        val data = store.transaction(owner) { current ->
            check((current.publishLeases[identifier]?.expiresAt ?: 0) <= nowSeconds()) {
                "別の画面でこの記事を公開中です。処理が終わってから再試行してください"
            }
            current.copy(publishLeases = current.publishLeases + (identifier to ArticlePublishLease(holder, current.generation + 1, nowSeconds() + 60)))
        }
        val lease = data.publishLeases.getValue(identifier)
        try {
            // This lock belongs to session memory and bounds live work in this process only.
            return withTimeout(45_000) { action(lease) }
        } finally {
            withContext(NonCancellable) {
                // Release failure must not hide a persisted receipt. Expiry permits recovery.
                runCatching { withTimeout(5_000) {
                    store.transaction(owner) { current ->
                        if (current.publishLeases[identifier] == lease)
                            current.copy(publishLeases = current.publishLeases - identifier) else current
                    }
                } }
            }
        }
    }
    private fun checkLease(data: ArticleStoreData, identifier: String, lease: ArticlePublishLease) {
        check(data.publishLeases[identifier] == lease && lease.expiresAt > nowSeconds()) {
            "公開処理の有効期限が切れました。保存した版の受理状況を確認して再試行してください"
        }
    }
    private suspend fun transmit(attempt: ArticlePublishAttempt, recovering: Boolean, lease: ArticlePublishLease): ArticlePublishAttempt {
        val current = store.read(owner)
        checkLease(current, attempt.draft.articleIdentifier, lease)
        if (current.attempts[attempt.id] != attempt) throw ArticleStoreConflict()
        val remaining = attempt.relays.filterValues { it.state != ArticleRelayState.Accepted }.keys
        val allowed = writableRelays().toSet()
        val targets = remaining.intersect(allowed)
        val result = if (targets.isEmpty()) RelayPublishResult(emptySet(), emptyMap()) else
            withTimeoutOrNull(15_000) { send(attempt.event, targets) }
                ?: RelayPublishResult(emptySet(), targets.associateWith { "受理応答がタイムアウトしました" })
        val updated = attempt.copy(draftCleanupError = null, relays = attempt.relays + remaining.associateWith { relay ->
            val old = attempt.relays.getValue(relay)
            val priorUnknown = old.state == ArticleRelayState.Unknown || (recovering && old.state == ArticleRelayState.Pending)
            val reason = result.failedRelays[relay]?.take(1000) ?: "受理応答を確認できません"
            when {
                relay in result.succeededRelays -> ArticleRelayStatus(ArticleRelayState.Accepted)
                relay !in allowed -> ArticleRelayStatus(if (priorUnknown) ArticleRelayState.Unknown else ArticleRelayState.Rejected,
                    "現在の設定で書き込みが許可されていません" + if (priorUnknown) "。前回の送信結果は未確認です" else "")
                relay in result.rejectedRelays -> ArticleRelayStatus(if (priorUnknown) ArticleRelayState.Unknown else ArticleRelayState.Rejected,
                    reason + if (priorUnknown) "（今回の再送は拒否。前回の送信結果は未確認）" else "")
                else -> ArticleRelayStatus(ArticleRelayState.Unknown, reason)
            }
        })
        try { store.transaction(owner) { current ->
            checkLease(current, attempt.draft.articleIdentifier, lease)
            if (current.attempts[attempt.id] != attempt) throw ArticleStoreConflict()
            val oldArchive = current.archives[attempt.draft.articleIdentifier]?.let { current.attempts[it] }
            val archiveIsOlder = oldArchive == null || oldArchive.event.createdAt <= updated.event.createdAt
            current.copy(
                attempts = current.attempts + (attempt.id to updated),
                archives = if (updated.accepted && archiveIsOlder)
                    current.archives + (attempt.draft.articleIdentifier to attempt.id) else current.archives,
            )
        }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error("公開結果の保存に失敗しました。署名済みイベントを保持しています: ${e.message}") }
        return if (updated.complete) cleanPublishedDraft(updated) else updated
    }
    private suspend fun cleanPublishedDraft(attempt: ArticlePublishAttempt): ArticlePublishAttempt {
        try { return store.cleanPublishedDraft(owner, attempt.id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val retained = attempt.copy(draftCleanupError = e.message ?: "下書きを削除できませんでした")
            store.transaction(owner) { current ->
                if (current.attempts[attempt.id] != attempt) throw ArticleStoreConflict()
                current.copy(attempts = current.attempts + (attempt.id to retained))
            }
            return retained
        }
    }
}
internal fun validateArticleDraft(draft: ArticleDraft): String? = when {
    draft.title.isBlank() -> "タイトルを入力してください"
    draft.title.length > 200 -> "タイトルは200文字までです"
    draft.summary.length > 500 -> "概要は500文字までです"
    draft.content.isBlank() -> "本文を入力してください"
    draft.content.length > ARTICLE_CONTENT_MAX_LENGTH -> "生成後の本文は10,000文字までです"
    draft.coverImageUrl.isNotBlank() && !draft.coverImageUrl.startsWith("https://", true) -> "カバー画像にはHTTPS URLを指定してください"
    com.nostr.torinos.ui.article.containsHtml(draft.content) -> "記事本文にHTMLは使用できません"
    else -> validateMatometr(draft)
}
