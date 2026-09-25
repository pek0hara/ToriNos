package com.nostr.torinos.ui.article

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.article.toEngagementState
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.NoteEngagementCommand
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.NoteTarget
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.ui.timeline.NoteEngagementCoordinator
import com.nostr.torinos.article.articleEngagementFilters
import com.nostr.torinos.article.articleTopLevelComments
import com.nostr.torinos.article.summarizeArticleReactions
import com.nostr.torinos.model.ArticleItem
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.articleAddress
import com.nostr.torinos.model.latestArticleVersions
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.toArticleMeta
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.util.BoundedLruCache
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ArticleListState(
    val articles: List<ArticleItem> = emptyList(),
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

/** 記事一覧で取得する著者の範囲。 */
sealed interface ArticleAuthorScope {
    /** 選択中リレーの全著者。 */
    data object All : ArticleAuthorScope

    /** ログイン中アカウントのフォロー中ユーザー。 */
    data object Following : ArticleAuthorScope

    /** 指定した1人の著者。 */
    data class Only(val pubkey: String) : ArticleAuthorScope
}

/** 記事一覧の取得条件。著者の範囲とトピックはAND条件でリレーのフィルターへ変換する。 */
data class ArticleQuery(
    val authorScope: ArticleAuthorScope = ArticleAuthorScope.All,
    val topic: String? = null,
)

class ArticleListViewModel(
    initialQuery: ArticleQuery,
    private val relayUrl: String? = null,
    private val accountSession: AccountSession? = null,
) : SafeViewModel() {
    private var query = initialQuery
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
            accountSession?.followRepository?.followedPubkeys?.drop(1)?.collect {
                if (query.authorScope == ArticleAuthorScope.Following) refresh()
            }
        }
        launch {
            ArticleMemoryCache.articleEvents.collect { localEvent ->
                if (!localEvent.matches(relayUrl) || !matchesQuery(localEvent.event)) return@collect
                rawEvents[localEvent.event.id] = localEvent.event
                applyLocalArticleEvent(localEvent.event)
                fetchMissingProfiles()
            }
        }
        launch {
            ArticleMemoryCache.articleDeletions.collect { deletion ->
                if (!deletion.matches(relayUrl) || !matchesAuthorScope(deletion.pubkey)) return@collect
                removeRawArticle(deletion.address)
                applyLocalArticleDeletion(deletion.address)
            }
        }
        refresh()
    }

    /** 取得条件を変更する。条件が変わった場合だけ一覧を読み込み直す。 */
    fun setQuery(newQuery: ArticleQuery) {
        if (newQuery == query) return
        query = newQuery
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
            val authors = when (val scope = query.authorScope) {
                ArticleAuthorScope.All -> null
                ArticleAuthorScope.Following -> followedPubkeys().toList()
                is ArticleAuthorScope.Only -> listOf(scope.pubkey)
            }
            // フォローが0人のとき、空のauthorsをリレーへ送らず結果なしとして扱う。
            val events = if (authors?.isEmpty() == true) {
                emptyList()
            } else {
                fetchArticleEvents(
                    filter = NostrFilter(
                        kinds = listOf(NIP23_ARTICLE_KIND),
                        authors = authors,
                        tTags = query.topic?.let(::listOf),
                        until = until,
                        limit = ARTICLE_PAGE_SIZE,
                    ),
                    relayUrl = relayUrl,
                )
            }
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
            isInitialLoad = false,
            isLoadingMore = false,
            canLoadMore = lastPageSize >= ARTICLE_PAGE_SIZE,
            error = null,
        )
    }

    /** 9.1: 単一のローカル公開イベントだけを比較し、既存のarticlesに差分反映する。 */
    private fun applyLocalArticleEvent(event: NostrEvent) {
        val meta = event.toArticleMeta() ?: return
        if (accountSession?.muteStore?.isMuted(event.pubkey) == true) return
        val candidate = ArticleItem(event = event, meta = meta, authorProfile = _state.value.profiles[event.pubkey])
        val articles = _state.value.articles.withUpsertedArticle(candidate)
        if (articles === _state.value.articles) return
        ArticleMemoryCache.putArticles(relayUrl, listOf(candidate))
        _state.value = _state.value.copy(articles = articles)
    }

    /** 9.1: 削除対象のaddressだけをarticlesから取り除く。 */
    private fun applyLocalArticleDeletion(address: String) {
        val articles = _state.value.articles
        if (articles.none { it.address == address }) return
        _state.value = _state.value.copy(articles = articles.filterNot { it.address == address })
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

    /** 9.1: プロフィール変更では記事本文を再解析せず、著者プロフィールだけを差し替える。 */
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
        _state.value = _state.value.copy(articles = articles)
    }

    private fun followedPubkeys(): Set<String> =
        accountSession?.followRepository?.followedPubkeys?.value.orEmpty()

    private fun matchesAuthorScope(pubkey: String): Boolean = when (val scope = query.authorScope) {
        ArticleAuthorScope.All -> true
        ArticleAuthorScope.Following -> pubkey in followedPubkeys()
        is ArticleAuthorScope.Only -> scope.pubkey == pubkey
    }

    private fun matchesQuery(event: NostrEvent): Boolean {
        if (!matchesAuthorScope(event.pubkey)) return false
        val topic = query.topic ?: return true
        return event.toArticleMeta()?.topics?.contains(topic) == true
    }

    /** 著者指定の一覧では、記事が1件もなくても見出し用に著者のプロフィールを取得する。 */
    private suspend fun fetchMissingProfiles() {
        val scopedAuthor = (query.authorScope as? ArticleAuthorScope.Only)?.pubkey
        val missing = (listOfNotNull(scopedAuthor) + rawEvents.values.map { it.pubkey })
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
    val engagement: ArticleEngagementState = ArticleEngagementState.Loading,
)

/** 記事詳細のリアクションとコメントの取得状態。 */
sealed interface ArticleEngagementState {
    data object Loading : ArticleEngagementState

    data class Loaded(
        val reactions: NoteEngagementState,
        val comments: List<NostrEvent>,
        val commentProfiles: Map<String, NostrProfile>,
        val reactionError: String? = null,
    ) : ArticleEngagementState

    data class Failed(val message: String) : ArticleEngagementState
}

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
    private var engagementJob: Job? = null
    private val engagementCoordinator = NoteEngagementCoordinator(accountSession?.signer)
    private var nextEngagementOperationId = 0L

    /** ログイン中（署名できる）ときだけリアクションを送れる。 */
    val canReact: Boolean = accountSession?.signer != null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        quoteJob?.cancel()
        engagementJob?.cancel()
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
                if (latest != null) {
                    engagementJob = launch { loadEngagement(latest) }
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

    fun like() {
        val article = _state.value.article ?: return
        runReaction(EngagementRequest.AddLike, NoteEngagementCommand.AddLike(article.reactionTarget()))
    }

    fun unlike() {
        val reactionId = loadedEngagement()?.reactions?.ownLikeEventId ?: return
        runReaction(EngagementRequest.RemoveLike, NoteEngagementCommand.RemoveReaction(reactionId))
    }

    fun react(option: ReactionOption) {
        val article = _state.value.article ?: return
        runReaction(
            EngagementRequest.AddEmoji(option),
            NoteEngagementCommand.AddEmoji(article.reactionTarget(), option),
        )
    }

    fun unreact(option: ReactionOption) {
        val reactionId = loadedEngagement()?.reactions?.ownEmojiReactionEventIds?.get(option.key) ?: return
        runReaction(EngagementRequest.RemoveEmoji(option), NoteEngagementCommand.RemoveReaction(reactionId))
    }

    private fun loadedEngagement(): ArticleEngagementState.Loaded? =
        _state.value.engagement as? ArticleEngagementState.Loaded

    private fun updateLoadedEngagement(transform: (ArticleEngagementState.Loaded) -> ArticleEngagementState.Loaded) {
        val loaded = loadedEngagement() ?: return
        _state.value = _state.value.copy(engagement = transform(loaded))
    }

    /** 楽観的に集計へ反映してから送信し、失敗したら巻き戻す。 */
    private fun runReaction(request: EngagementRequest, command: NoteEngagementCommand) {
        val loaded = loadedEngagement() ?: return
        val operationId = EngagementOperationId("article-${++nextEngagementOperationId}")
        val optimistic = engagementCoordinator.begin(loaded.reactions, operationId, request)
        if (optimistic == loaded.reactions) return
        updateLoadedEngagement { it.copy(reactions = optimistic, reactionError = null) }
        launch {
            engagementCoordinator.execute(command)
                .onSuccess { published ->
                    updateLoadedEngagement {
                        it.copy(reactions = engagementCoordinator.commit(it.reactions, operationId, published.id))
                    }
                }
                .onFailure { error ->
                    val message = when (command) {
                        is NoteEngagementCommand.RemoveReaction -> "リアクションの解除に失敗しました"
                        else -> "リアクションの送信に失敗しました"
                    }
                    updateLoadedEngagement {
                        it.copy(
                            reactions = engagementCoordinator.rollback(it.reactions, operationId),
                            reactionError = error.message?.let { detail -> "$message: $detail" } ?: message,
                        )
                    }
                }
        }
    }

    /** 記事へのリアクション対象。版IDに加えてaddressと種類を付け、編集後の版にも引き継がれるようにする。 */
    private fun ArticleItem.reactionTarget(): NoteTarget = NoteTarget(
        eventId = event.id,
        eventPubkey = event.pubkey,
        address = address,
        kind = NIP23_ARTICLE_KIND,
    )

    /** 記事へのリアクションとコメントを、有効な全リレーから有限取得する。 */
    private suspend fun loadEngagement(article: ArticleItem) {
        val articleEventIds = setOf(article.event.id)
        val ownPubkey = accountSession?.signer?.pubkey
        val isMuted: (String) -> Boolean = { accountSession?.muteStore?.isMuted(it) == true }
        try {
            val events = fetchEventsOnce(
                articleEngagementFilters(article.address, articleEventIds, ARTICLE_ENGAGEMENT_LIMIT),
            )
            val comments = articleTopLevelComments(events, article.address, articleEventIds, isMuted)
            val reactions = summarizeArticleReactions(events, article.address, articleEventIds, ownPubkey, isMuted)
            val commenters = comments.map { it.pubkey }.distinct()
            _state.value = _state.value.copy(
                engagement = ArticleEngagementState.Loaded(
                    reactions = reactions.toEngagementState(),
                    comments = comments,
                    commentProfiles = ProfileRepository.getCached(commenters),
                ),
            )
            val missing = commenters.filterNot { it in ProfileRepository.getCached(commenters) }
            if (missing.isEmpty()) return
            val fetched = fetchProfiles(missing, relayUrl)
            val loaded = _state.value.engagement as? ArticleEngagementState.Loaded ?: return
            _state.value = _state.value.copy(
                engagement = loaded.copy(commentProfiles = loaded.commentProfiles + fetched),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = _state.value.copy(
                engagement = ArticleEngagementState.Failed(e.message ?: "リアクションとコメントを読み込めませんでした"),
            )
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

/** 有効な全リレーへ有限購読し、全リレーの完了またはタイムアウトまでに届いたイベントを返す。 */
private suspend fun fetchEventsOnce(filters: List<NostrFilter>): List<NostrEvent> {
    val events = linkedMapOf<String, NostrEvent>()
    val session = NostrRepository.openSubscription(
        SubscriptionSpec(
            id = articleSubscriptionId("article-engagement", filters),
            filters = filters,
            behavior = SubscriptionBehavior.Fetch(timeoutMillis = ARTICLE_FETCH_TIMEOUT_MS),
        ),
    )
    try {
        session.signals.takeWhile { signal ->
            if (signal is SubscriptionSignal.Event) events[signal.event.id] = signal.event
            signal !is SubscriptionSignal.FetchCompleted
        }.collect { }
    } finally {
        withContext(NonCancellable) { session.close() }
    }
    return events.values.toList()
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
private const val ARTICLE_ENGAGEMENT_LIMIT = 500
private const val PROFILE_FETCH_TIMEOUT_MS = 5_000L
private const val PROFILE_FETCH_LIMIT = 200
private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
private const val NIP09_DELETION_KIND = 5

private fun articleSubscriptionId(prefix: String, key: Any): String =
    "$prefix-${Clock.System.now().toEpochMilliseconds()}-${key.hashCode()}-${Random.nextInt()}"
