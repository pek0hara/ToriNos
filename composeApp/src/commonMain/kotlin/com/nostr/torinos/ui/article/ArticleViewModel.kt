package com.nostr.torinos.ui.article

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.ArticleAuthorItem
import com.nostr.torinos.model.ArticleItem
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.articleAddress
import com.nostr.torinos.model.latestArticleVersions
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.toArticleAuthors
import com.nostr.torinos.model.toArticleMeta
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.util.BoundedLruCache
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class ArticleHubTab(val label: String) {
    Articles("記事一覧"),
    Users("ユーザー"),
}

data class ArticleListState(
    val articles: List<ArticleItem> = emptyList(),
    val authors: List<ArticleAuthorItem> = emptyList(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val isInitialLoad: Boolean = true,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
)

internal object ArticleMemoryCache {
    private val articlesByRelayAndAddress =
        BoundedLruCache<String, ArticleItem>(MaximumArticleEntries)
    private val eventsByRelayAndId =
        BoundedLruCache<String, NostrEvent>(MaximumEventEntries)
    private val localArticleEvents = MutableSharedFlow<LocalArticleEvent>(extraBufferCapacity = 16)
    private val localArticleDeletions = MutableSharedFlow<LocalArticleDeletion>(extraBufferCapacity = 16)

    val articleEvents = localArticleEvents
    val articleDeletions = localArticleDeletions

    fun putArticles(relayUrl: String?, articles: List<ArticleItem>) {
        articles.forEach { article ->
            articlesByRelayAndAddress[articleKey(relayUrl, article.address)] = article
        }
    }

    fun putEvent(relayUrl: String?, event: NostrEvent) {
        eventsByRelayAndId[eventKey(relayUrl, event.id)] = event
    }

    fun publishArticle(event: NostrEvent, relayUrls: Collection<String>) {
        val meta = event.toArticleMeta() ?: return
        val article = ArticleItem(event = event, meta = meta)
        putArticles(null, listOf(article))
        relayUrls.forEach { relayUrl ->
            putArticles(relayUrl, listOf(article))
        }
        localArticleEvents.tryEmit(LocalArticleEvent(event, relayUrls.toSet()))
    }

    fun deleteArticle(article: ArticleItem, relayUrls: Collection<String>) {
        removeArticle(null, article.address)
        relayUrls.forEach { relayUrl ->
            removeArticle(relayUrl, article.address)
        }
        localArticleDeletions.tryEmit(
            LocalArticleDeletion(
                address = article.address,
                pubkey = article.event.pubkey,
                relayUrls = relayUrls.toSet(),
            ),
        )
    }

    fun article(relayUrl: String?, pubkey: String, identifier: String): ArticleItem? =
        articlesByRelayAndAddress[articleKey(relayUrl, articleAddress(pubkey, identifier))]

    fun events(relayUrl: String?, eventIds: Collection<String>): Map<String, NostrEvent> =
        eventIds.mapNotNull { eventId ->
            eventsByRelayAndId[eventKey(relayUrl, eventId)]?.let { eventId to it }
        }.toMap()

    fun removeArticle(relayUrl: String?, address: String) {
        articlesByRelayAndAddress.remove(articleKey(relayUrl, address))
    }

    private fun articleKey(relayUrl: String?, address: String): String =
        "${relayUrl.orEmpty()}|$address"

    private fun eventKey(relayUrl: String?, eventId: String): String =
        "${relayUrl.orEmpty()}|$eventId"

    private const val MaximumArticleEntries = 500
    private const val MaximumEventEntries = 1_000
}

internal data class LocalArticleEvent(
    val event: NostrEvent,
    val relayUrls: Set<String>,
) {
    fun matches(relayUrl: String?): Boolean =
        relayUrl == null || relayUrl in relayUrls
}

internal data class LocalArticleDeletion(
    val address: String,
    val pubkey: String,
    val relayUrls: Set<String>,
) {
    fun matches(relayUrl: String?): Boolean =
        relayUrl == null || relayUrl in relayUrls
}

internal val articleDisplayOrder: Comparator<ArticleItem> =
    compareByDescending<ArticleItem> { it.sortTime }.thenByDescending { it.event.createdAt }

internal val authorDisplayOrder: Comparator<ArticleAuthorItem> =
    compareByDescending<ArticleAuthorItem> { it.latestArticle.sortTime }.thenByDescending { it.latestArticle.event.createdAt }

/**
 * 対象addressだけを比較して追加/置換する(9.1: 新規イベントで全件のlatestArticleVersions()を
 * 再実行しない)。既存より古いバージョンなら変化なしとして同じリスト参照を返す。
 */
internal fun List<ArticleItem>.withUpsertedArticle(candidate: ArticleItem): List<ArticleItem> {
    val existingIndex = indexOfFirst { it.address == candidate.address }
    if (existingIndex >= 0 && this[existingIndex].event.createdAt >= candidate.event.createdAt) {
        return this
    }
    val result = ArrayList<ArticleItem>(size + 1)
    for (i in indices) {
        if (i != existingIndex) result += this[i]
    }
    val insertAt = result.indexOfFirst { articleDisplayOrder.compare(candidate, it) < 0 }
        .let { if (it < 0) result.size else it }
    result.add(insertAt, candidate)
    return result
}

/** 指定pubkeyの著者表示モデルだけを、更新後のarticlesから再計算して差し替える。 */
internal fun List<ArticleAuthorItem>.withUpdatedAuthor(pubkey: String, articles: List<ArticleItem>): List<ArticleAuthorItem> {
    val itemsForAuthor = articles.filter { it.event.pubkey == pubkey }
    val withoutExisting = filterNot { it.pubkey == pubkey }
    if (itemsForAuthor.isEmpty()) return withoutExisting
    val latest = itemsForAuthor.maxWith(compareBy<ArticleItem> { it.sortTime }.thenBy { it.event.createdAt })
    val authorItem = ArticleAuthorItem(
        pubkey = pubkey,
        profile = latest.authorProfile,
        articleCount = itemsForAuthor.size,
        latestArticle = latest,
    )
    val insertAt = withoutExisting.indexOfFirst { authorDisplayOrder.compare(authorItem, it) < 0 }
        .let { if (it < 0) withoutExisting.size else it }
    val result = ArrayList<ArticleAuthorItem>(withoutExisting.size + 1)
    result.addAll(withoutExisting)
    result.add(insertAt, authorItem)
    return result
}

/** 記事一覧の取得条件。 */
sealed interface ArticleQuery {
    /** 選択中リレーの全著者の記事。 */
    data object Global : ArticleQuery

    /** 指定した著者の記事。 */
    data class Author(val pubkey: String) : ArticleQuery
}

private val ArticleQuery.authorPubkey: String?
    get() = (this as? ArticleQuery.Author)?.pubkey

private fun ArticleQuery.matchesAuthor(pubkey: String): Boolean =
    authorPubkey == null || authorPubkey == pubkey

class ArticleListViewModel(
    private val query: ArticleQuery,
    private val relayUrl: String? = null,
    private val accountSession: AccountSession? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(ArticleListState())
    val state: StateFlow<ArticleListState> = _state.asStateFlow()

    private val rawEvents = linkedMapOf<String, NostrEvent>()
    private var oldestCreatedAt: Long? = null
    private var lastPageSize = 0
    private var loadJob: Job? = null

    init {
        launch {
            accountSession?.muteStore?.mutedPubkeys?.drop(1)?.collect { updateStateFromEvents() }
        }
        launch {
            ArticleMemoryCache.articleEvents.collect { localEvent ->
                if (!localEvent.matches(relayUrl) || !query.matchesAuthor(localEvent.event.pubkey)) return@collect
                rawEvents[localEvent.event.id] = localEvent.event
                applyLocalArticleEvent(localEvent.event)
                fetchMissingProfiles()
            }
        }
        launch {
            ArticleMemoryCache.articleDeletions.collect { deletion ->
                if (!deletion.matches(relayUrl) || !query.matchesAuthor(deletion.pubkey)) return@collect
                removeRawArticle(deletion.address)
                applyLocalArticleDeletion(deletion.address, deletion.pubkey)
            }
        }
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        rawEvents.clear()
        oldestCreatedAt = null
        lastPageSize = 0
        _state.value = ArticleListState()
        loadJob = launch { loadPage(until = null, append = false) }
    }

    fun loadMore() {
        val state = _state.value
        if (state.isInitialLoad || state.isLoadingMore || !state.canLoadMore) return
        val until = oldestCreatedAt?.minus(1) ?: return
        loadJob = launch { loadPage(until = until, append = true) }
    }

    private suspend fun loadPage(until: Long?, append: Boolean) {
        _state.value = _state.value.copy(
            isInitialLoad = !append && _state.value.articles.isEmpty(),
            isLoadingMore = append,
            error = null,
        )
        try {
            val events = fetchArticleEvents(
                filter = NostrFilter(
                    kinds = listOf(NIP23_ARTICLE_KIND),
                    authors = query.authorPubkey?.let(::listOf),
                    until = until,
                    limit = ARTICLE_PAGE_SIZE,
                ),
                relayUrl = relayUrl,
            )
            lastPageSize = events.size
            events.forEach { event ->
                rawEvents[event.id] = event
                oldestCreatedAt = minOf(oldestCreatedAt ?: event.createdAt, event.createdAt)
            }
            trimRawEventWindow()
            updateStateFromEvents()
            fetchMissingProfiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = _state.value.copy(
                isInitialLoad = false,
                isLoadingMore = false,
                error = e.message ?: "記事を読み込めませんでした",
            )
        }
    }

    private fun updateStateFromEvents() {
        val profiles = _state.value.profiles
        val articles = rawEvents.values
            .filterNot { accountSession?.muteStore?.isMuted(it.pubkey) == true }
            .mapNotNull { event ->
                val meta = event.toArticleMeta() ?: return@mapNotNull null
                ArticleItem(event = event, meta = meta, authorProfile = profiles[event.pubkey])
            }
            .latestArticleVersions()
        ArticleMemoryCache.putArticles(relayUrl, articles)
        _state.value = _state.value.copy(
            articles = articles,
            authors = articles.toArticleAuthors(),
            isInitialLoad = false,
            isLoadingMore = false,
            canLoadMore = lastPageSize >= ARTICLE_PAGE_SIZE,
            error = null,
        )
    }

    /** 9.1: 単一のローカル公開イベントだけを比較し、既存のarticles/authorsに差分反映する。 */
    private fun applyLocalArticleEvent(event: NostrEvent) {
        val meta = event.toArticleMeta() ?: return
        if (accountSession?.muteStore?.isMuted(event.pubkey) == true) return
        val candidate = ArticleItem(event = event, meta = meta, authorProfile = _state.value.profiles[event.pubkey])
        val articles = _state.value.articles.withUpsertedArticle(candidate)
        if (articles === _state.value.articles) return
        ArticleMemoryCache.putArticles(relayUrl, listOf(candidate))
        _state.value = _state.value.copy(
            articles = articles,
            authors = _state.value.authors.withUpdatedAuthor(event.pubkey, articles),
        )
    }

    /** 9.1: 削除対象のaddressだけをarticles/authorsから取り除く。 */
    private fun applyLocalArticleDeletion(address: String, pubkey: String) {
        val articles = _state.value.articles
        if (articles.none { it.address == address }) return
        val updatedArticles = articles.filterNot { it.address == address }
        _state.value = _state.value.copy(
            articles = updatedArticles,
            authors = _state.value.authors.withUpdatedAuthor(pubkey, updatedArticles),
        )
    }

    /**
     * 9.2: ページ追加のたびに増え続けるrawEventsを、直近ARTICLE_RAW_EVENT_WINDOW(1,000)件へ収める。
     * loadMore()は現在のスクロール位置付近(末尾)へ追記する形でしか呼ばれないため、末尾側は常に
     * 現在の閲覧アンカーを含む。先頭側(挿入が最も古い = 時系列で最も新しい = 既にスクロールし
     * 終えた記事)から間引くことで、表示中の窓を飛ばさずに上限を維持する。
     */
    private fun trimRawEventWindow() {
        while (rawEvents.size > ARTICLE_RAW_EVENT_WINDOW) {
            val eldestKey = rawEvents.keys.firstOrNull() ?: break
            rawEvents.remove(eldestKey)
        }
    }

    private fun removeRawArticle(address: String) {
        rawEvents.entries.removeAll { (_, event) ->
            val meta = event.toArticleMeta() ?: return@removeAll false
            articleAddress(event.pubkey, meta.identifier) == address
        }
    }

    /** 9.1: プロフィール変更では記事本文を再解析せず、著者表示モデルだけを差し替える。 */
    private fun applyProfileUpdates(newProfiles: Map<String, NostrProfile>) {
        if (newProfiles.isEmpty()) return
        _state.value = _state.value.copy(profiles = _state.value.profiles + newProfiles)
        val changedArticles = mutableListOf<ArticleItem>()
        val articles = _state.value.articles.map { article ->
            val profile = newProfiles[article.event.pubkey]
            if (profile != null && article.authorProfile != profile) {
                article.copy(authorProfile = profile).also { changedArticles += it }
            } else {
                article
            }
        }
        if (changedArticles.isEmpty()) return
        ArticleMemoryCache.putArticles(relayUrl, changedArticles)
        var authors = _state.value.authors
        changedArticles.map { it.event.pubkey }.distinct().forEach { pubkey ->
            authors = authors.withUpdatedAuthor(pubkey, articles)
        }
        _state.value = _state.value.copy(articles = articles, authors = authors)
    }

    /** 著者指定の一覧では、記事が1件もなくても見出し用に著者のプロフィールを取得する。 */
    private suspend fun fetchMissingProfiles() {
        val missing = (listOfNotNull(query.authorPubkey) + rawEvents.values.map { it.pubkey })
            .distinct()
            .filterNot { it in _state.value.profiles }
        val cachedProfiles = ProfileRepository.getCached(missing)
        if (cachedProfiles.isNotEmpty()) {
            applyProfileUpdates(cachedProfiles)
        }
        val uncached = missing.filterNot { it in cachedProfiles }
        if (uncached.isEmpty()) return
        val profiles = fetchProfiles(uncached, relayUrl)
        if (profiles.isEmpty()) return
        applyProfileUpdates(profiles)
    }
}

data class ArticleDetailState(
    val article: ArticleItem? = null,
    val profile: NostrProfile? = null,
    val quotedEvents: Map<String, NostrEvent> = emptyMap(),
    val quotedProfiles: Map<String, NostrProfile> = emptyMap(),
    val loadingQuoteIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val deleteCompletedCount: Int = 0,
    val deleteError: String? = null,
    val error: String? = null,
)

class ArticleDetailViewModel(
    private val pubkey: String,
    private val identifier: String,
    private val relayUrl: String? = null,
    private val accountSession: AccountSession? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(ArticleDetailState())
    val state: StateFlow<ArticleDetailState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var quoteJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        quoteJob?.cancel()
        _state.value = ArticleDetailState()
        loadJob = launch {
            try {
                val latest = ArticleMemoryCache.article(relayUrl, pubkey, identifier)
                    ?: fetchLatestArticleByAddress(pubkey, identifier, relayUrl)
                val quoteIds = latest?.event?.let(::quotedEventIds).orEmpty()
                val cachedQuotedEvents = ArticleMemoryCache.events(relayUrl, quoteIds)
                val profilePubkeys = (listOf(pubkey) + cachedQuotedEvents.values.map { it.pubkey }).distinct()
                val cachedProfiles = ProfileRepository.getCached(profilePubkeys)
                val profile = cachedProfiles[pubkey]
                    ?: latest?.authorProfile
                    ?: ProfileRepository.getCached(pubkey)
                _state.value = ArticleDetailState(
                    article = latest?.copy(authorProfile = profile),
                    profile = profile,
                    quotedEvents = cachedQuotedEvents,
                    quotedProfiles = cachedProfiles + listOfNotNull(profile?.let { pubkey to it }),
                    loadingQuoteIds = quoteIds.filterNot { it in cachedQuotedEvents }.toSet(),
                    isLoading = false,
                    error = if (latest == null) "記事が見つかりません" else null,
                )
                if (latest != null && profile == null) {
                    launch { fetchAuthorProfile() }
                }
                val missingQuoteIds = quoteIds.filterNot { it in cachedQuotedEvents }
                if (latest != null && missingQuoteIds.isNotEmpty()) {
                    quoteJob = launch { fetchQuotedEvents(missingQuoteIds) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = ArticleDetailState(
                    isLoading = false,
                    error = e.message ?: "記事を読み込めませんでした",
                )
            }
        }
    }

    fun deleteArticle() {
        val article = _state.value.article ?: run {
            _state.value = _state.value.copy(error = "記事が読み込まれていません")
            return
        }
        if (_state.value.isDeleting) return

        val relayUrls = RelayStore.writableRelayUrlsSnapshot()
        if (relayUrls.isEmpty()) {
            _state.value = _state.value.copy(deleteError = "削除要求の送信先リレーが設定されていません")
            return
        }

        _state.value = _state.value.copy(isDeleting = true, deleteError = null)
        launch {
            try {
                val signer = accountSession?.signer ?: error("秘密鍵が設定されていません")
                val deletion = signer.sign(
                    content = "記事を削除",
                    kind = NIP09_DELETION_KIND,
                    tags = listOf(
                        listOf("a", article.address),
                        listOf("e", article.event.id),
                        listOf("k", NIP23_ARTICLE_KIND.toString()),
                        listOf("client", "ToriNos"),
                    ),
                )
                if (deletion.pubkey != article.event.pubkey) {
                    error("この記事を投稿したアカウントでログインしてください")
                }
                NostrRepository.publishToRelays(deletion, relayUrls)
                ArticleMemoryCache.deleteArticle(article, relayUrls)
                _state.value = _state.value.copy(
                    article = null,
                    isDeleting = false,
                    deleteError = null,
                    deleteCompletedCount = _state.value.deleteCompletedCount + 1,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isDeleting = false,
                    deleteError = e.message ?: "記事の削除要求を送信できませんでした",
                )
            }
        }
    }

    private suspend fun fetchAuthorProfile() {
        val profile = fetchProfiles(listOf(pubkey), relayUrl)[pubkey] ?: return
        val cur = _state.value
        val article = cur.article ?: return
        _state.value = cur.copy(
            article = article.copy(authorProfile = profile),
            profile = profile,
            quotedProfiles = cur.quotedProfiles + (pubkey to profile),
        )
    }

    private suspend fun fetchQuotedEvents(quoteIds: List<String>) {
        val eventIds = quoteIds.distinct()
        if (eventIds.isEmpty()) return
        val subId = articleSubscriptionId("article-detail-quote", eventIds)
        try {
            coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    NostrRepository.events(subId).collect { event ->
                        if (event.id !in eventIds) return@collect
                        ArticleMemoryCache.putEvent(relayUrl, event)
                        val quoteProfiles = fetchProfiles(listOf(event.pubkey), relayUrl)
                        val cur = _state.value
                        _state.value = cur.copy(
                            quotedEvents = cur.quotedEvents + (event.id to event),
                            quotedProfiles = cur.quotedProfiles + quoteProfiles,
                            loadingQuoteIds = cur.loadingQuoteIds - event.id,
                        )
                    }
                }
                launch {
                    withTimeoutOrNull(ARTICLE_FETCH_TIMEOUT_MS) {
                        NostrRepository.eose(subId).first()
                    }
                    _state.value = _state.value.copy(loadingQuoteIds = emptySet())
                }
                NostrRepository.subscribe(
                    subId,
                    NostrFilter(ids = eventIds, limit = eventIds.size),
                    relayUrl = relayUrl,
                )
                awaitCancellation()
            }
        } finally {
            runCatching { NostrRepository.closeSuspending(subId) }
        }
    }
}

internal suspend fun fetchLatestArticleByAddress(
    pubkey: String,
    identifier: String,
    relayUrl: String?,
): ArticleItem? {
    val dTagEvents = fetchArticleEvents(
        filter = NostrFilter(
            kinds = listOf(NIP23_ARTICLE_KIND),
            authors = listOf(pubkey),
            dTags = listOf(identifier),
            limit = 10,
        ),
        relayUrl = relayUrl,
    )
    val exact = dTagEvents.toArticleItems()
        .filter { it.meta.identifier == identifier }
        .maxByOrNull { it.event.createdAt }
    if (exact != null) return exact

    return fetchArticleEvents(
        filter = NostrFilter(
            kinds = listOf(NIP23_ARTICLE_KIND),
            authors = listOf(pubkey),
            limit = ARTICLE_DETAIL_AUTHOR_FALLBACK_LIMIT,
        ),
        relayUrl = relayUrl,
    )
        .toArticleItems()
        .filter { it.meta.identifier == identifier }
        .maxByOrNull { it.event.createdAt }
}

private fun List<NostrEvent>.toArticleItems(): List<ArticleItem> =
    mapNotNull { event ->
        val meta = event.toArticleMeta() ?: return@mapNotNull null
        ArticleItem(event = event, meta = meta)
    }

private suspend fun fetchArticleEvents(
    filter: NostrFilter,
    relayUrl: String? = null,
): List<NostrEvent> = coroutineScope {
    val subId = articleSubscriptionId("article", filter)
    val mutex = Mutex()
    val events = mutableListOf<NostrEvent>()
    var collector: Job? = null
    try {
        collector = launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(subId).collect { event ->
                if (event.kind == NIP23_ARTICLE_KIND) {
                    mutex.withLock { events += event }
                }
            }
        }
        NostrRepository.subscribe(subId, filter, relayUrl = relayUrl)
        withTimeoutOrNull(ARTICLE_FETCH_TIMEOUT_MS) {
            NostrRepository.eose(subId).first()
        }
        mutex.withLock { events.distinctBy { it.id } }
    } finally {
        runCatching { NostrRepository.close(subId) }
        collector?.cancelAndJoin()
    }
}

private suspend fun fetchProfiles(
    pubkeys: List<String>,
    relayUrl: String? = null,
): Map<String, NostrProfile> {
    val authors = pubkeys.distinct().take(PROFILE_FETCH_LIMIT)
    if (authors.isEmpty()) return emptyMap()
    return ProfileRepository.awaitProfiles(
        pubkeys = authors.toSet(),
        policy = ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
        relayHint = relayUrl,
        timeoutMillis = PROFILE_FETCH_TIMEOUT_MS,
    )
}

private const val ARTICLE_PAGE_SIZE = 50
private const val ARTICLE_RAW_EVENT_WINDOW = 1_000
private const val ARTICLE_DETAIL_AUTHOR_FALLBACK_LIMIT = 100
private const val ARTICLE_FETCH_TIMEOUT_MS = 8_000L
private const val PROFILE_FETCH_TIMEOUT_MS = 5_000L
private const val PROFILE_FETCH_LIMIT = 200
private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
private const val NIP09_DELETION_KIND = 5

private fun articleSubscriptionId(prefix: String, key: Any): String =
    "$prefix-${Clock.System.now().toEpochMilliseconds()}-${key.hashCode()}-${Random.nextInt()}"
