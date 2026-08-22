package com.nostr.torinos.ui.article

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.signEvent
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
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.SafeViewModel
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
    private val articlesByRelayAndAddress = mutableMapOf<String, ArticleItem>()
    private val eventsByRelayAndId = mutableMapOf<String, NostrEvent>()
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

class ArticleHubViewModel(private val relayUrl: String? = null) : SafeViewModel() {
    private val _state = MutableStateFlow(ArticleListState())
    val state: StateFlow<ArticleListState> = _state.asStateFlow()

    private val rawEvents = linkedMapOf<String, NostrEvent>()
    private var oldestCreatedAt: Long? = null
    private var lastPageSize = 0
    private var loadJob: Job? = null

    init {
        launch {
            MuteStore.mutedPubkeys.drop(1).collect { updateStateFromEvents() }
        }
        launch {
            ArticleMemoryCache.articleEvents.collect { localEvent ->
                if (!localEvent.matches(relayUrl)) return@collect
                rawEvents[localEvent.event.id] = localEvent.event
                updateStateFromEvents()
                fetchMissingProfiles()
            }
        }
        launch {
            ArticleMemoryCache.articleDeletions.collect { deletion ->
                if (!deletion.matches(relayUrl)) return@collect
                removeRawArticle(deletion.address)
                updateStateFromEvents()
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
            .filterNot { MuteStore.isMuted(it.pubkey) }
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

    private fun removeRawArticle(address: String) {
        rawEvents.entries.removeAll { (_, event) ->
            val meta = event.toArticleMeta() ?: return@removeAll false
            articleAddress(event.pubkey, meta.identifier) == address
        }
    }

    private suspend fun fetchMissingProfiles() {
        val missing = rawEvents.values
            .map { it.pubkey }
            .distinct()
            .filterNot { it in _state.value.profiles }
        val cachedProfiles = ProfileRepository.getCached(missing)
        if (cachedProfiles.isNotEmpty()) {
            _state.value = _state.value.copy(profiles = _state.value.profiles + cachedProfiles)
            updateStateFromEvents()
        }
        val uncached = missing.filterNot { it in cachedProfiles }
        if (uncached.isEmpty()) return
        val profiles = fetchProfiles(uncached, relayUrl)
        if (profiles.isEmpty()) return
        _state.value = _state.value.copy(profiles = _state.value.profiles + profiles)
        updateStateFromEvents()
    }
}

class UserArticleListViewModel(
    private val pubkey: String,
    private val relayUrl: String? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(ArticleListState())
    val state: StateFlow<ArticleListState> = _state.asStateFlow()

    private val rawEvents = linkedMapOf<String, NostrEvent>()
    private var oldestCreatedAt: Long? = null
    private var lastPageSize = 0
    private var loadJob: Job? = null

    init {
        launch {
            MuteStore.mutedPubkeys.drop(1).collect { updateStateFromEvents() }
        }
        launch {
            ArticleMemoryCache.articleEvents.collect { localEvent ->
                if (!localEvent.matches(relayUrl) || localEvent.event.pubkey != pubkey) return@collect
                rawEvents[localEvent.event.id] = localEvent.event
                updateStateFromEvents()
                fetchProfile()
            }
        }
        launch {
            ArticleMemoryCache.articleDeletions.collect { deletion ->
                if (!deletion.matches(relayUrl) || deletion.pubkey != pubkey) return@collect
                removeRawArticle(deletion.address)
                updateStateFromEvents()
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
                    authors = listOf(pubkey),
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
            updateStateFromEvents()
            fetchProfile()
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
            .filterNot { MuteStore.isMuted(it.pubkey) }
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

    private fun removeRawArticle(address: String) {
        rawEvents.entries.removeAll { (_, event) ->
            val meta = event.toArticleMeta() ?: return@removeAll false
            articleAddress(event.pubkey, meta.identifier) == address
        }
    }

    private suspend fun fetchProfile() {
        if (pubkey in _state.value.profiles) return
        ProfileRepository.getCached(pubkey)?.let { cachedProfile ->
            _state.value = _state.value.copy(profiles = _state.value.profiles + (pubkey to cachedProfile))
            updateStateFromEvents()
            return
        }
        val profile = fetchProfiles(listOf(pubkey), relayUrl)
        if (profile.isEmpty()) return
        _state.value = _state.value.copy(profiles = _state.value.profiles + profile)
        updateStateFromEvents()
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

        val relayUrls = RelayStore.enabledRelayUrlsSnapshot()
        if (relayUrls.isEmpty()) {
            _state.value = _state.value.copy(deleteError = "削除要求の送信先リレーが設定されていません")
            return
        }

        _state.value = _state.value.copy(isDeleting = true, deleteError = null)
        launch {
            try {
                val privateKeyHex = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が設定されていません")
                val deletion = signEvent(
                    privateKeyHex = privateKeyHex,
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

private suspend fun fetchEventsByIds(
    ids: List<String>,
    relayUrl: String? = null,
): List<NostrEvent> = coroutineScope {
    val eventIds = ids.distinct()
    if (eventIds.isEmpty()) return@coroutineScope emptyList()
    val subId = articleSubscriptionId("article-quote", eventIds)
    val mutex = Mutex()
    val events = mutableListOf<NostrEvent>()
    var collector: Job? = null
    try {
        collector = launch(start = CoroutineStart.UNDISPATCHED) {
            NostrRepository.events(subId).collect { event ->
                if (event.id in eventIds) {
                    mutex.withLock { events += event }
                }
            }
        }
        NostrRepository.subscribe(
            subId,
            NostrFilter(ids = eventIds, limit = eventIds.size),
            relayUrl = relayUrl,
        )
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
private const val ARTICLE_DETAIL_AUTHOR_FALLBACK_LIMIT = 100
private const val ARTICLE_FETCH_TIMEOUT_MS = 8_000L
private const val PROFILE_FETCH_TIMEOUT_MS = 5_000L
private const val PROFILE_FETCH_LIMIT = 200
private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
private const val NIP09_DELETION_KIND = 5

private fun articleSubscriptionId(prefix: String, key: Any): String =
    "$prefix-${Clock.System.now().toEpochMilliseconds()}-${key.hashCode()}-${Random.nextInt()}"
