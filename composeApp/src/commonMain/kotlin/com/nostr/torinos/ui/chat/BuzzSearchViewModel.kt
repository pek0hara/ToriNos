package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.BUZZ_SEARCH_DEBOUNCE_MILLIS
import com.nostr.torinos.chat.BUZZ_SEARCH_FIRST_LIMIT
import com.nostr.torinos.chat.BUZZ_SEARCH_PAGE_LIMIT
import com.nostr.torinos.chat.BuzzMessageHit
import com.nostr.torinos.chat.BuzzRelaySearchMode
import com.nostr.torinos.chat.BuzzSearchChannel
import com.nostr.torinos.chat.BuzzSearchCorpus
import com.nostr.torinos.chat.BuzzSearchPerson
import com.nostr.torinos.chat.ChatAccount
import com.nostr.torinos.chat.ChatListCache
import com.nostr.torinos.chat.CommunityRosterLoad
import com.nostr.torinos.chat.GroupLayout
import com.nostr.torinos.chat.GroupMetadata
import com.nostr.torinos.chat.GroupRef
import com.nostr.torinos.chat.buzzLocalPeople
import com.nostr.torinos.chat.buzzMessageHits
import com.nostr.torinos.chat.buzzRecentSearchesKey
import com.nostr.torinos.chat.buzzRelaySearchMode
import com.nostr.torinos.chat.buzzSearchPeople
import com.nostr.torinos.chat.decodeRecentSearches
import com.nostr.torinos.chat.encodeRecentSearches
import com.nostr.torinos.chat.mergeBuzzSearchHits
import com.nostr.torinos.chat.rankBuzzSearchChannels
import com.nostr.torinos.chat.recordRecentSearch
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.LocalSettingsStorage
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.util.appLog
import com.nostr.torinos.util.isBuzzSearchTraceEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** 検索の結果の絞り込み。表示だけを切り替え、検索はいつも全部行う（公式と同じ）。 */
enum class BuzzSearchFilter(val label: String) {
    All("すべて"), Messages("メッセージ"), Channels("チャンネル"), People("人"),
}

data class BuzzSearchUiState(
    /** いま結果を出している入力（前後の空白を除いたもの）。空なら最近の検索を出す。 */
    val query: String = "",
    val filter: BuzzSearchFilter = BuzzSearchFilter.All,
    /** 手元の当たり（先）とリレーの当たり（後）をまとめたもの。 */
    val messages: List<BuzzMessageHit> = emptyList(),
    val channels: List<BuzzSearchChannel> = emptyList(),
    val people: List<BuzzSearchPerson> = emptyList(),
    val messagesLoading: Boolean = false,
    val peopleLoading: Boolean = false,
    /** メッセージの検索にリレーが答えなかった（再試行を出す）。 */
    val messagesFailed: Boolean = false,
    /** 「さらに表示」を出す（リレーに続きがある、または手元の控えをまださかのぼれる）。 */
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    val recent: List<String> = emptyList(),
    /** チャンネルの ID → グループ情報。当たりの「#チャンネル」と、開く画面の種類に使う。 */
    val channelInfo: Map<String, GroupMetadata> = emptyMap(),
) {
    val isLoading: Boolean get() = messagesLoading || peopleLoading

    private val showsMessages get() = filter == BuzzSearchFilter.All || filter == BuzzSearchFilter.Messages
    private val showsChannels get() = filter == BuzzSearchFilter.All || filter == BuzzSearchFilter.Channels
    private val showsPeople get() = filter == BuzzSearchFilter.All || filter == BuzzSearchFilter.People

    val shownMessages: List<BuzzMessageHit> get() = if (showsMessages) messages else emptyList()
    val shownChannels: List<BuzzSearchChannel> get() = if (showsChannels) channels else emptyList()
    val shownPeople: List<BuzzSearchPerson> get() = if (showsPeople) people else emptyList()
    val showsMessageFailure: Boolean get() = showsMessages && messagesFailed
    val showsLoadMore: Boolean get() = showsMessages && canLoadMore

    /** 選んでいる絞り込みに出すものが1つも無い（読み込みが終わっていれば「一致なし」）。 */
    val hasNoResults: Boolean
        get() = shownMessages.isEmpty() && shownChannels.isEmpty() && shownPeople.isEmpty() &&
            !showsMessageFailure && !showsLoadMore

    fun layoutOf(ref: GroupRef): GroupLayout = channelInfo[ref.groupId]?.layout ?: GroupLayout.Chat
}

/**
 * Buzz コミュニティ内の検索（docs/buzz-search-design.md 2 章）。
 * 入力のたびに手元の控え（最近の発言）を部分一致で探し、入力が止まって 300ms 後にリレーへ聞く。
 * 「さらに表示」で、リレーの続きと手元の控えを 500 件ずつさかのぼる。
 */
class BuzzSearchViewModel(
    private val account: ChatAccount?,
    private val relayUrl: String,
) : SafeViewModel() {
    private val _state = MutableStateFlow(BuzzSearchUiState())
    val state: StateFlow<BuzzSearchUiState> = _state.asStateFlow()

    private val signsSilently = account?.signsSilently == true
    private var candidates: List<BuzzSearchChannel> = emptyList()
    private var relayPubkey: String? = null
    private var searchJob: Job? = null
    private var localJob: Job? = null
    /** 新しい入力のたびに増やす。遅れて届いた前の答えを捨てる。 */
    private var generation = 0
    /** 一覧に無いチャンネルの情報を、もう聞いた ID。 */
    private val askedChannelIds = mutableSetOf<String>()
    private val releaseRelay: () -> Unit = account?.holdBuzzRelay(relayUrl) ?: {}
    private val recentKey = account?.let { buzzRecentSearchesKey(it.pubkey, relayUrl) }

    // ---- 手元の控え ----
    private var corpus: BuzzSearchCorpus? = null
    private var corpusMayHaveMore = false
    private var localHits: List<BuzzMessageHit> = emptyList()

    // ---- リレーの検索（いまの入力のもの） ----
    private var relayEvents: List<NostrEvent> = emptyList()
    private var relayMode: BuzzRelaySearchMode = BuzzRelaySearchMode.Socket
    /** 次に読む `/query` のページ（500 件ずつ）。続きが無ければ null。 */
    private var relayNextPage: Int? = null

    // ---- 人（外部の署名器のときの手元の候補） ----
    private var rosterPeople: List<String>? = null

    init {
        launch {
            recentKey?.let { key -> _state.update { it.copy(recent = decodeRecentSearches(LocalSettingsStorage.getString(key))) } }
        }
        launch { loadChannels() }
        launch { loadCorpus() }
        if (!signsSilently) launch { loadRoster() }
    }

    private fun trace(message: () -> String) {
        if (isBuzzSearchTraceEnabled) appLog("[BuzzSearch] ${message()}")
    }

    private suspend fun loadChannels() {
        val account = account ?: return
        relayPubkey = account.buzzRelayPubkey(relayUrl)
        // 先にチャット一覧の控え（参加中のチャンネル）で出し、リレーの答えで置き換える。
        val cached = ChatListCache.load(account.pubkey).rows
            .filter { it.relayUrl == relayUrl && it.buzzJoined }
            .mapNotNull { row -> row.metadata?.let { BuzzSearchChannel(row.ref, it, joined = true) } }
        if (candidates.isEmpty() && cached.isNotEmpty()) setCandidates(cached)
        // 答えがそろわなければ控えのまま（無いと決めつけない）。
        account.loadBuzzSearchChannels(relayUrl)?.let { loaded ->
            // 参加中を一覧の順（控えの並び = 最近の発言順）で先に置く。
            val order = cached.withIndex().associate { (i, c) -> c.ref to i }
            setCandidates(loaded.sortedWith(compareBy<BuzzSearchChannel> { !it.joined }.thenBy { order[it.ref] ?: Int.MAX_VALUE }))
        }
    }

    private fun setCandidates(channels: List<BuzzSearchChannel>) {
        candidates = channels
        _state.update { current ->
            current.copy(
                channelInfo = current.channelInfo + channels.associate { it.ref.groupId to it.metadata },
                channels = if (current.query.isEmpty()) emptyList() else rankBuzzSearchChannels(channels, current.query),
            )
        }
    }

    private suspend fun loadCorpus() {
        val account = account ?: return
        val mark = TimeSource.Monotonic.markNow()
        val (loaded, more) = account.loadBuzzSearchCorpus(relayUrl) ?: return
        corpus = loaded
        corpusMayHaveMore = more
        trace { "corpus loaded messages=${loaded.messageCount} more=$more ms=${mark.elapsedNow().inWholeMilliseconds}" }
        refreshLocal()
    }

    private suspend fun loadRoster() {
        val account = account ?: return
        val roster = account.fetchCommunityRoster(relayUrl) as? CommunityRosterLoad.Loaded ?: return
        val members = roster.members.map { it.pubkey }
        rosterPeople = members
        ProfileRepository.ensureProfiles(members.toSet(), ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = relayUrl)
    }

    private fun localPeople(query: String): List<BuzzSearchPerson>? {
        val members = rosterPeople ?: return null
        val profiles = ProfileRepository.getCached(members)
        val candidates = members.mapNotNull { pubkey -> profiles[pubkey]?.let { BuzzSearchPerson(pubkey, it) } }
        return buzzLocalPeople(candidates, query, account?.pubkey)
    }

    /** 入力が変わった。手元はすぐ、リレーは止まってから探す。 */
    fun onQueryChange(text: String) {
        val query = text.trim()
        // 前後の空白を足しただけなら検索し直さない（失敗したときは「再試行」で）。
        if (query == _state.value.query) return
        searchJob?.cancel()
        val current = ++generation
        relayEvents = emptyList()
        relayNextPage = null
        localHits = emptyList()
        if (query.isEmpty()) {
            localJob?.cancel()
            _state.update {
                it.copy(query = "", messages = emptyList(), channels = emptyList(), people = emptyList(),
                    messagesLoading = false, peopleLoading = false, messagesFailed = false, canLoadMore = false, loadingMore = false)
            }
            return
        }
        val people = if (signsSilently) null else localPeople(query)
        _state.update {
            it.copy(
                query = query,
                channels = rankBuzzSearchChannels(candidates, query),
                messages = emptyList(),
                people = people ?: emptyList(),
                // 読み込み中は入力のときから出す（待っている間に「一致なし」を出さない）。
                messagesLoading = true,
                peopleLoading = people == null,
                messagesFailed = false,
                canLoadMore = false,
                loadingMore = false,
            )
        }
        refreshLocal()
        searchJob = launch {
            delay(BUZZ_SEARCH_DEBOUNCE_MILLIS)
            launch { searchRelayFirst(query, current) }
            if (people == null) launch { searchPeople(query, current) }
        }
    }

    /** 手元の控えで探し直す（入力が変わった、控えが増えた）。 */
    private fun refreshLocal() {
        val query = _state.value.query
        val corpus = corpus ?: return
        if (query.isEmpty()) return
        val current = generation
        localJob?.cancel()
        localJob = launch {
            val mark = TimeSource.Monotonic.markNow()
            val hits = withContext(Dispatchers.Default) { corpus.search(query) }
            if (current != generation) return@launch
            trace { "local query=${query.length}chars corpus=${corpus.messageCount} hits=${hits.size} ms=${mark.elapsedNow().inWholeMilliseconds}" }
            localHits = hits
            publishMessages()
            ensureAuthors(hits)
        }
    }

    /** 手元とリレーの当たりをまとめて出す。リレーの当たりにも控えの削除・編集を反映する。 */
    private fun publishMessages() {
        val corpus = corpus
        val deleted = corpus?.deletedIds().orEmpty()
        val relayHits = buzzMessageHits(relayEvents, relayUrl, relayPubkey, corpus?.editedBodies().orEmpty())
            .filter { it.id !in deleted }
        _state.update {
            it.copy(
                messages = mergeBuzzSearchHits(localHits, relayHits),
                canLoadMore = relayNextPage != null || (corpusMayHaveMore && corpus != null),
            )
        }
    }

    private suspend fun searchRelayFirst(query: String, current: Int) {
        val account = account ?: return
        val mode = buzzRelaySearchMode(query, signsSilently)
        val limit = if (mode == BuzzRelaySearchMode.Socket) BUZZ_SEARCH_PAGE_LIMIT else BUZZ_SEARCH_FIRST_LIMIT
        val mark = TimeSource.Monotonic.markNow()
        val page = account.searchBuzzMessagesPage(relayUrl, query, mode, limit, page = 1)
        if (current != generation) return
        trace { "relay mode=$mode limit=$limit hits=${page?.events?.size} more=${page?.mayHaveMore} ms=${mark.elapsedNow().inWholeMilliseconds}" }
        relayMode = mode
        relayEvents = page?.events.orEmpty()
        // 最初の 50 件の続きは、500 件ずつのページの 1 ページ目から（重なりはまとめるときに除く）。
        relayNextPage = if (page?.mayHaveMore == true && mode != BuzzRelaySearchMode.Socket) 1 else null
        _state.update { it.copy(messagesLoading = false, messagesFailed = page == null) }
        publishMessages()
        afterRelayHits()
    }

    private suspend fun afterRelayHits() {
        val hits = buzzMessageHits(relayEvents, relayUrl, relayPubkey)
        ensureAuthors(hits)
        // DM や一覧に載らないチャンネルは、ID を指定して情報を読む（見出しに使うだけ。待たずに出す）。
        val missing = hits.map { it.ref.groupId }.distinct().filter { it !in _state.value.channelInfo && askedChannelIds.add(it) }
        if (missing.isNotEmpty()) {
            val found = account?.fetchBuzzChannelMetadata(relayUrl, missing).orEmpty()
            if (found.isNotEmpty()) _state.update { it.copy(channelInfo = it.channelInfo + found) }
        }
    }

    private fun ensureAuthors(hits: List<BuzzMessageHit>) {
        if (hits.isEmpty()) return
        launch { ProfileRepository.ensureProfiles(hits.map { it.author }.toSet(), ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = relayUrl) }
    }

    private suspend fun searchPeople(query: String, current: Int) {
        val events = account?.searchBuzzPeople(relayUrl, query).orEmpty()
        if (current != generation) return
        _state.update { it.copy(people = buzzSearchPeople(events, account?.pubkey), peopleLoading = false) }
    }

    /** 「さらに表示」: リレーの続きと手元の控えを 500 件ずつさかのぼる。 */
    fun loadMore() {
        val account = account ?: return
        val query = _state.value.query
        if (query.isEmpty() || _state.value.loadingMore || !_state.value.canLoadMore) return
        val current = generation
        _state.update { it.copy(loadingMore = true) }
        launch {
            val relayJob = relayNextPage?.let { pageNumber ->
                launch {
                    val mark = TimeSource.Monotonic.markNow()
                    val page = account.searchBuzzMessagesPage(relayUrl, query, relayMode, BUZZ_SEARCH_PAGE_LIMIT, pageNumber)
                    if (current != generation) return@launch
                    trace { "relay more page=$pageNumber hits=${page?.events?.size} more=${page?.mayHaveMore} ms=${mark.elapsedNow().inWholeMilliseconds}" }
                    if (page != null) {
                        val seen = relayEvents.mapTo(HashSet()) { it.id }
                        relayEvents = relayEvents + page.events.filter { it.id !in seen }
                        relayNextPage = if (page.mayHaveMore) pageNumber + 1 else null
                    }
                }
            }
            val corpusJob = corpus?.takeIf { corpusMayHaveMore }?.let { corpus ->
                launch {
                    val mark = TimeSource.Monotonic.markNow()
                    val more = account.extendBuzzSearchCorpus(relayUrl, corpus)
                    trace { "corpus more messages=${corpus.messageCount} more=$more ms=${mark.elapsedNow().inWholeMilliseconds}" }
                    if (more != null) corpusMayHaveMore = more
                }
            }
            relayJob?.join()
            corpusJob?.join()
            if (current != generation) return@launch
            _state.update { it.copy(loadingMore = false) }
            publishMessages()
            refreshLocal()
            afterRelayHits()
        }
    }

    fun retryMessages() {
        val query = _state.value.query
        if (query.isEmpty()) return
        val current = generation
        _state.update { it.copy(messagesLoading = true, messagesFailed = false) }
        launch { searchRelayFirst(query, current) }
    }

    fun setFilter(filter: BuzzSearchFilter) {
        _state.update { it.copy(filter = filter) }
    }

    /** キーボードで決定した、または結果を押した。いまの入力を最近の検索に残す。 */
    fun recordQuery(text: String = _state.value.query) {
        val key = recentKey ?: return
        val next = recordRecentSearch(_state.value.recent, text)
        if (next == _state.value.recent) return
        _state.update { it.copy(recent = next) }
        launch { LocalSettingsStorage.putString(key, encodeRecentSearches(next)) }
    }

    fun clearRecent() {
        val key = recentKey ?: return
        _state.update { it.copy(recent = emptyList()) }
        launch { LocalSettingsStorage.putString(key, "") }
    }

    override fun onCleared() {
        releaseRelay()
        super.onCleared()
    }
}
