package com.nostr.torinos.ui.article

import com.nostr.torinos.model.ArticleAuthorItem
import com.nostr.torinos.model.ArticleItem
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.articleAddress
import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.model.latestArticleVersions
import com.nostr.torinos.model.toArticleAuthors
import com.nostr.torinos.model.toArticleMeta
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.SafeViewModel
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class ArticleHubTab(val label: String) {
    Articles("記事"),
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

private object ArticleMemoryCache {
    private val articlesByRelayAndAddress = mutableMapOf<String, ArticleItem>()
    private val profilesByRelayAndPubkey = mutableMapOf<String, NostrProfile>()

    fun putArticles(relayUrl: String?, articles: List<ArticleItem>) {
        articles.forEach { article ->
            articlesByRelayAndAddress[articleKey(relayUrl, article.address)] = article
        }
    }

    fun putProfiles(relayUrl: String?, profiles: Map<String, NostrProfile>) {
        profiles.forEach { (pubkey, profile) ->
            profilesByRelayAndPubkey[profileKey(relayUrl, pubkey)] = profile
        }
    }

    fun article(relayUrl: String?, pubkey: String, identifier: String): ArticleItem? =
        articlesByRelayAndAddress[articleKey(relayUrl, articleAddress(pubkey, identifier))]

    fun profile(relayUrl: String?, pubkey: String): NostrProfile? =
        profilesByRelayAndPubkey[profileKey(relayUrl, pubkey)]

    private fun articleKey(relayUrl: String?, address: String): String =
        "${relayUrl.orEmpty()}|$address"

    private fun profileKey(relayUrl: String?, pubkey: String): String =
        "${relayUrl.orEmpty()}|$pubkey"
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

    private suspend fun fetchMissingProfiles() {
        val missing = rawEvents.values
            .map { it.pubkey }
            .distinct()
            .filterNot { it in _state.value.profiles }
        if (missing.isEmpty()) return
        val profiles = fetchProfiles(missing, relayUrl)
        if (profiles.isEmpty()) return
        ArticleMemoryCache.putProfiles(relayUrl, profiles)
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

    private suspend fun fetchProfile() {
        if (pubkey in _state.value.profiles) return
        val profile = fetchProfiles(listOf(pubkey), relayUrl)
        if (profile.isEmpty()) return
        ArticleMemoryCache.putProfiles(relayUrl, profile)
        _state.value = _state.value.copy(profiles = _state.value.profiles + profile)
        updateStateFromEvents()
    }
}

data class ArticleDetailState(
    val article: ArticleItem? = null,
    val profile: NostrProfile? = null,
    val quotedEvents: Map<String, NostrEvent> = emptyMap(),
    val quotedProfiles: Map<String, NostrProfile> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

class ArticleDetailViewModel(
    private val pubkey: String,
    private val identifier: String,
    private val relayUrl: String? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(ArticleDetailState())
    val state: StateFlow<ArticleDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = ArticleDetailState()
        launch {
            try {
                val latest = ArticleMemoryCache.article(relayUrl, pubkey, identifier)
                    ?: fetchLatestArticleByAddress(pubkey, identifier, relayUrl)
                val quoteIds = latest
                    ?.event
                    ?.content
                    ?.let { extractNostrEventReferences(it).map { reference -> reference.eventId }.distinct() }
                    .orEmpty()
                val quotedEvents = fetchEventsByIds(quoteIds, relayUrl).associateBy { it.id }
                val profilePubkeys = listOf(pubkey) + quotedEvents.values.map { it.pubkey }
                val profiles = fetchProfiles(profilePubkeys, relayUrl)
                ArticleMemoryCache.putProfiles(relayUrl, profiles)
                val profile = profiles[pubkey]
                    ?: latest?.authorProfile
                    ?: ArticleMemoryCache.profile(relayUrl, pubkey)
                _state.value = ArticleDetailState(
                    article = latest?.copy(authorProfile = profile),
                    profile = profile,
                    quotedEvents = quotedEvents,
                    quotedProfiles = profiles,
                    isLoading = false,
                    error = if (latest == null) "記事が見つかりません" else null,
                )
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
}

private suspend fun fetchLatestArticleByAddress(
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
    val subId = "article-${Clock.System.now().epochSeconds}-${filter.hashCode()}"
    val mutex = Mutex()
    val events = mutableListOf<NostrEvent>()
    var collector: Job? = null
    try {
        collector = launch {
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
    val subId = "article-quote-${Clock.System.now().epochSeconds}-${eventIds.hashCode()}"
    val mutex = Mutex()
    val events = mutableListOf<NostrEvent>()
    var collector: Job? = null
    try {
        collector = launch {
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
    val profiles = fetchProfilesFromRelay(authors, relayUrl).toMutableMap()
    if (profiles.size >= authors.size) return profiles

    val fallbackRelayUrls = RelayStore.enabledRelayUrlsSnapshot()
        .filter { it != relayUrl }
    for (fallbackRelayUrl in fallbackRelayUrls) {
        val missing = authors.filterNot { it in profiles }
        if (missing.isEmpty()) break
        profiles += fetchProfilesFromRelay(missing, fallbackRelayUrl)
    }
    return profiles
}

private suspend fun fetchProfilesFromRelay(
    authors: List<String>,
    relayUrl: String?,
): Map<String, NostrProfile> = coroutineScope {
    val subId = "article-prof-${Clock.System.now().epochSeconds}-${authors.hashCode()}"
    val mutex = Mutex()
    val profiles = mutableMapOf<String, NostrProfile>()
    var collector: Job? = null
    try {
        collector = launch {
            NostrRepository.events(subId).collect { event ->
                if (event.kind == 0 && event.pubkey in authors) {
                    event.toProfile()?.let { profile ->
                        mutex.withLock { profiles[event.pubkey] = profile }
                    }
                }
            }
        }
        NostrRepository.subscribe(
            subId,
            NostrFilter(kinds = listOf(0), authors = authors, limit = authors.size),
            relayUrl = relayUrl,
        )
        withTimeoutOrNull(PROFILE_FETCH_TIMEOUT_MS) {
            NostrRepository.eose(subId).first()
        }
        mutex.withLock { profiles.toMap() }
    } finally {
        runCatching { NostrRepository.close(subId) }
        collector?.cancelAndJoin()
    }
}

private const val ARTICLE_PAGE_SIZE = 50
private const val ARTICLE_DETAIL_AUTHOR_FALLBACK_LIMIT = 100
private const val ARTICLE_FETCH_TIMEOUT_MS = 8_000L
private const val PROFILE_FETCH_TIMEOUT_MS = 5_000L
private const val PROFILE_FETCH_LIMIT = 200
