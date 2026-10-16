package com.nostr.torinos.article

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.account.decodeUnlessSignerFails
import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.matches
import com.nostr.torinos.network.*
import com.nostr.torinos.ui.article.buildArticleTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.time.Clock

internal interface ArticleDraftTransport {
    val loadIssues: List<ArticleDraftLoadIssue> get() = emptyList()
    /** Relays that did not finish the last load; drafts stored only there may be missing or stale. */
    val incompleteRelays: List<String> get() = emptyList()
    suspend fun load(): List<ArticleDraft>
    /** Like [load], but reports the drafts known so far each time a relay finishes, starting with the first. */
    suspend fun loadProgressively(onProgress: suspend (List<ArticleDraft>) -> Unit): List<ArticleDraft> = load()
    /** `d` of the account's published articles, so drafts without their base version still show as updates. */
    suspend fun loadPublishedIdentifiers(): Set<String> = emptySet()
    suspend fun save(draft: ArticleDraft)
    suspend fun delete(draft: ArticleDraft)
}
@Serializable internal data class ArticleDraftLoadIssue(val identifier: String, val eventId: String, val message: String)

/** NIP-37 unsigned article event. The optional extension preserves structured editing. */
@Serializable internal data class ArticleDraftPayload(
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int = NIP23_ARTICLE_KIND,
    val tags: List<List<String>>,
    val content: String,
    val torinos: ArticleDraft? = null,
    val torinosContentLayout: ArticleDraftContentLayout? = null,
)

/** Article drafts use the existing self-encrypted relay draft mechanism, without a device DB. */
internal class ArticleDraftRepository(
    private val signer: AccountSigner,
    private val readRelays: () -> Set<String>,
    private val writableRelays: () -> List<String>,
    private val ensureActive: () -> Unit = {},
    private val openSession: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    private val send: suspend (NostrEvent, Collection<String>) -> RelayPublishResult = { event, urls ->
        NostrRepository.publishToRelaysWithResult(event, urls, awaitAcceptance = true)
    },
    private val validate: (NostrEvent) -> Boolean = ::isValidEvent,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : ArticleDraftTransport {
    override var loadIssues: List<ArticleDraftLoadIssue> = emptyList()
        private set
    override var incompleteRelays: List<String> = emptyList()
        private set
    private val latestEvents = mutableMapOf<String, NostrEvent>()
    private val lastAttemptedAt = mutableMapOf<String, Long>()
    override suspend fun load(): List<ArticleDraft> = loadProgressively {}
    override suspend fun loadProgressively(onProgress: suspend (List<ArticleDraft>) -> Unit): List<ArticleDraft> {
        ensureActive()
        val relays = readRelays()
        check(relays.isNotEmpty()) { "記事下書きを取得するリレーがありません" }
        val filter = NostrFilter(kinds = listOf(ARTICLE_DRAFT_KIND), authors = listOf(signer.pubkey),
            parentKindTags = listOf(NIP23_ARTICLE_KIND.toString()), limit = 1000)
        val session = openSession(SubscriptionSpec("article-drafts-${articleLocalId()}", listOf(filter),
            RelayTarget.Explicit(relays), SubscriptionBehavior.Fetch(10_000), deduplicateEvents = false))
        val events = mutableListOf<NostrEvent>()
        // Decrypting is the costly part, and each relay that finishes re-reads the drafts so far.
        val decoded = mutableMapOf<String, ArticleDraft?>()
        fun snapshot(): Pair<Map<String, NostrEvent>, List<NostrEvent>> {
            // Include our accepted writes, so a relay's delayed indexing cannot revert a just-saved draft.
            val latest = (events + latestEvents.values).groupBy { it.tags.tagValue("d") }.mapNotNull { (id, versions) ->
                if (id == null) null else id to versions.sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id }).first()
            }.toMap()
            return latest to latest.values.filter { it.content.isNotEmpty() }
        }
        fun decode(event: NostrEvent) = decoded.getOrPut(event.id) { decodeArticleDraft(event, signer) }
        var outcomes: Map<String, RelayOutcome>? = null
        try {
            session.signals.untilFetchCompleted().collect { signal -> when (signal) {
                is SubscriptionSignal.Event -> if (filter.matches(signal.event) && validate(signal.event)) events += signal.event
                is SubscriptionSignal.Eose -> { ensureActive(); onProgress(snapshot().second.mapNotNull(::decode)) }
                is SubscriptionSignal.FetchCompleted -> outcomes = signal.outcomes
                else -> Unit
            } }
        } finally { withContext(NonCancellable) { session.close() } }
        ensureActive()
        // One unreachable relay must not hide every draft; only a fetch with no completed relay is a failure.
        val completed = outcomes.orEmpty().filterValues { it == RelayOutcome.Eose }.keys
        check(completed.isNotEmpty()) { "記事下書きの取得を完了できませんでした。再取得してください" }
        val (latest, present) = snapshot()
        val issues = mutableListOf<ArticleDraftLoadIssue>()
        val drafts = present.mapNotNull { event ->
            decode(event).also { draft ->
                if (draft == null) issues += ArticleDraftLoadIssue(event.tags.tagValue("d").orEmpty(), event.id,
                    "この下書きを読み込めませんでした。元データは削除されていません")
            }
        }
        latestEvents.putAll(latest)
        loadIssues = issues
        incompleteRelays = outcomes.orEmpty().keys.filterNot { it in completed }.sorted()
        return drafts
    }
    override suspend fun loadPublishedIdentifiers(): Set<String> {
        ensureActive()
        val relays = readRelays()
        if (relays.isEmpty()) return emptySet()
        val filter = NostrFilter(kinds = listOf(NIP23_ARTICLE_KIND), authors = listOf(signer.pubkey), limit = 1000)
        val session = openSession(SubscriptionSpec("article-published-${articleLocalId()}", listOf(filter),
            RelayTarget.Explicit(relays), SubscriptionBehavior.Fetch(10_000)))
        val identifiers = mutableSetOf<String>()
        try {
            session.signals.untilFetchCompleted().collect { signal ->
                if (signal is SubscriptionSignal.Event && filter.matches(signal.event) && validate(signal.event))
                    signal.event.tags.tagValue("d")?.let(identifiers::add)
            }
        } finally { withContext(NonCancellable) { session.close() } }
        return identifiers
    }
    override suspend fun save(draft: ArticleDraft) {
        require(draft.ownerPubkey == signer.pubkey)
        val tags = buildArticleTags(draft.articleIdentifier, draft.title, draft.summary, draft.coverImageUrl,
            draft.topicsInput, draft.originalPublishedAt ?: draft.createdAt, draft.hasMatometrTag)
        val serialized = serializeArticleWithLayout(draft)
        val metadata = draft.copy(bodyMarkdown = "", references = draft.references.map { it.copy(commentaryMarkdown = "") })
        val payload = ArticleDraftPayload(signer.pubkey, draft.updatedAt, tags = tags, content = serialized.content,
            torinos = metadata, torinosContentLayout = serialized.layout)
        val plaintext = articleJson.encodeToString(payload)
        check(plaintext.encodeToByteArray().size <= 65_535) { "下書きの保存データが暗号化上限を超えています。本文・説明・設定を短くしてください" }
        write(draft, signer.encryptToSelf(plaintext))
    }
    override suspend fun delete(draft: ArticleDraft) { write(draft, "") }
    private suspend fun write(draft: ArticleDraft, content: String) {
        ensureActive()
        val urls = writableRelays().distinct()
        check(urls.isNotEmpty()) { "記事下書きの保存先リレーを設定してください" }
        val identifier = draft.relayDraftIdentifier ?: "article-${draft.draftId}"
        val minimum = maxOf(latestEvents[identifier]?.createdAt ?: 0, lastAttemptedAt[identifier] ?: 0) + 1
        check(minimum <= nowSeconds() + 5) { "下書きの時刻を確認してから再試行してください" }
        withTimeout(6_000) { while (nowSeconds() < minimum) delay(100) }
        ensureActive()
        val event = signer.sign(content, ARTICLE_DRAFT_KIND, listOf(listOf("d", identifier),
            listOf("k", NIP23_ARTICLE_KIND.toString()), listOf("client", "ToriNos")), nowSeconds())
        lastAttemptedAt[identifier] = event.createdAt
        val result = send(event, urls)
        ensureActive()
        check(result.succeededRelays.isNotEmpty()) { "記事下書きをリレーに保存できませんでした。入力を保持しています" }
        latestEvents[identifier] = event
    }
}

internal suspend fun decodeArticleDraft(event: NostrEvent, signer: AccountSigner): ArticleDraft? {
    if (event.kind != ARTICLE_DRAFT_KIND || event.pubkey != signer.pubkey ||
        event.tags.tagValue("k") != NIP23_ARTICLE_KIND.toString() || event.content.isEmpty()) return null
    val plaintext = decodeUnlessSignerFails { signer.decrypt(event.content, signer.pubkey) } ?: return null
    return try {
        val identifier = event.tags.tagValue("d") ?: return null
        val payload = articleJson.decodeFromString<ArticleDraftPayload>(plaintext)
        if (payload.pubkey != signer.pubkey || payload.kind != NIP23_ARTICLE_KIND) return null
        val extension = payload.torinos?.let { draft ->
            payload.torinosContentLayout?.restore(draft, payload.content) ?: draft
        }?.takeIf { draft -> draft.schemaVersion == 1 && draft.ownerPubkey == signer.pubkey &&
            draft.references.all { it.hasValidIds() } && draft.content == payload.content &&
            buildArticleTags(draft.articleIdentifier, draft.title, draft.summary, draft.coverImageUrl, draft.topicsInput,
                draft.originalPublishedAt ?: draft.createdAt, draft.hasMatometrTag) == payload.tags }
        (extension ?: ArticleDraft(draftId = identifier, ownerPubkey = signer.pubkey,
            articleIdentifier = payload.tags.tagValue("d") ?: "torinos-${articleLocalId()}",
            originalPublishedAt = payload.tags.tagValue("published_at")?.toLongOrNull(),
            title = payload.tags.tagValue("title").orEmpty(), summary = payload.tags.tagValue("summary").orEmpty(),
            bodyMarkdown = payload.content, coverImageUrl = payload.tags.tagValue("image").orEmpty(),
            topicsInput = payload.tags.filter { it.firstOrNull() == "t" }.mapNotNull { it.getOrNull(1) }.joinToString(" "),
            mode = if (payload.tags.any { it == listOf("t", "matometr") }) ArticleDraftMode.MatometrMarkdown else ArticleDraftMode.Regular,
            createdAt = event.createdAt, updatedAt = event.createdAt, revision = 1))
            .copy(relayDraftIdentifier = identifier, revision = (extension?.revision ?: 1).coerceAtLeast(1))
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }
}
private fun List<List<String>>.tagValue(name: String) = firstOrNull { it.firstOrNull() == name }?.getOrNull(1)
internal const val ARTICLE_DRAFT_KIND = 31234
/** Ends with [SubscriptionSignal.FetchCompleted] instead of waiting for the session to close its stream. */
private fun Flow<SubscriptionSignal>.untilFetchCompleted() = transformWhile { signal ->
    emit(signal)
    signal !is SubscriptionSignal.FetchCompleted
}
