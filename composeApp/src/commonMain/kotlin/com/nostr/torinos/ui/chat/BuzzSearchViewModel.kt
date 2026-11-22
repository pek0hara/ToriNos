package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.BUZZ_SEARCH_DEBOUNCE_MILLIS
import com.nostr.torinos.chat.BuzzMessageHit
import com.nostr.torinos.chat.BuzzSearchChannel
import com.nostr.torinos.chat.BuzzSearchPerson
import com.nostr.torinos.chat.ChatAccount
import com.nostr.torinos.chat.ChatListCache
import com.nostr.torinos.chat.GroupLayout
import com.nostr.torinos.chat.GroupMetadata
import com.nostr.torinos.chat.GroupRef
import com.nostr.torinos.chat.buzzMessageHits
import com.nostr.torinos.chat.buzzRecentSearchesKey
import com.nostr.torinos.chat.buzzSearchPeople
import com.nostr.torinos.chat.decodeRecentSearches
import com.nostr.torinos.chat.encodeRecentSearches
import com.nostr.torinos.chat.rankBuzzSearchChannels
import com.nostr.torinos.chat.recordRecentSearch
import com.nostr.torinos.network.LocalSettingsStorage
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 検索の結果の絞り込み。表示だけを切り替え、検索はいつも全部行う（公式と同じ）。 */
enum class BuzzSearchFilter(val label: String) {
    All("すべて"), Messages("メッセージ"), Channels("チャンネル"), People("人"),
}

data class BuzzSearchUiState(
    /** いま結果を出している入力（前後の空白を除いたもの）。空なら最近の検索を出す。 */
    val query: String = "",
    val filter: BuzzSearchFilter = BuzzSearchFilter.All,
    val messages: List<BuzzMessageHit> = emptyList(),
    val channels: List<BuzzSearchChannel> = emptyList(),
    val people: List<BuzzSearchPerson> = emptyList(),
    val messagesLoading: Boolean = false,
    val peopleLoading: Boolean = false,
    /** メッセージの検索にリレーが答えなかった（再試行を出す）。 */
    val messagesFailed: Boolean = false,
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

    /** 選んでいる絞り込みに出すものが1つも無い（読み込みが終わっていれば「一致なし」）。 */
    val hasNoResults: Boolean
        get() = shownMessages.isEmpty() && shownChannels.isEmpty() && shownPeople.isEmpty() && !showsMessageFailure

    fun layoutOf(ref: GroupRef): GroupLayout = channelInfo[ref.groupId]?.layout ?: GroupLayout.Chat
}

/**
 * Buzz コミュニティ内の検索（docs/buzz-search-design.md 3 章）。
 * 入力が止まって 300ms 後に、メッセージと人をリレーに聞き、チャンネルは手元の一覧から並べる。
 */
class BuzzSearchViewModel(
    private val account: ChatAccount?,
    private val relayUrl: String,
) : SafeViewModel() {
    private val _state = MutableStateFlow(BuzzSearchUiState())
    val state: StateFlow<BuzzSearchUiState> = _state.asStateFlow()

    private var candidates: List<BuzzSearchChannel> = emptyList()
    private var relayPubkey: String? = null
    private var searchJob: Job? = null
    /** 新しい入力のたびに増やす。遅れて届いた前の答えを捨てる。 */
    private var generation = 0
    /** 一覧に無いチャンネルの情報を、もう聞いた ID。 */
    private val askedChannelIds = mutableSetOf<String>()
    private val releaseRelay: () -> Unit = account?.holdBuzzRelay(relayUrl) ?: {}
    private val recentKey = account?.let { buzzRecentSearchesKey(it.pubkey, relayUrl) }

    init {
        launch {
            recentKey?.let { key -> _state.update { it.copy(recent = decodeRecentSearches(LocalSettingsStorage.getString(key))) } }
        }
        launch { loadChannels() }
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

    /** 入力が変わった。止まってから検索する。 */
    fun onQueryChange(text: String) {
        val query = text.trim()
        // 前後の空白を足しただけなら検索し直さない（失敗したときは「再試行」で）。
        if (query == _state.value.query) return
        searchJob?.cancel()
        val current = ++generation
        if (query.isEmpty()) {
            _state.update {
                it.copy(query = "", messages = emptyList(), channels = emptyList(), people = emptyList(),
                    messagesLoading = false, peopleLoading = false, messagesFailed = false)
            }
            return
        }
        // 読み込み中は入力のときから出す（待っている間に「一致なし」を出さない）。
        _state.update {
            it.copy(query = query, channels = rankBuzzSearchChannels(candidates, query),
                messagesLoading = true, peopleLoading = true, messagesFailed = false)
        }
        searchJob = launch {
            delay(BUZZ_SEARCH_DEBOUNCE_MILLIS)
            launch { searchMessages(query, current) }
            launch { searchPeople(query, current) }
        }
    }

    private suspend fun searchMessages(query: String, current: Int) {
        val events = account?.searchBuzzMessages(relayUrl, query)
        if (current != generation) return
        val hits = events?.let { buzzMessageHits(it, relayUrl, relayPubkey) }.orEmpty()
        _state.update { it.copy(messages = hits, messagesLoading = false, messagesFailed = events == null) }
        if (hits.isEmpty()) return
        ProfileRepository.ensureProfiles(hits.map { it.author }.toSet(), ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = relayUrl)
        // DM や一覧に載らないチャンネルは、ID を指定して情報を読む（見出しに使うだけ。待たずに出す）。
        val missing = hits.map { it.ref.groupId }.distinct().filter { it !in _state.value.channelInfo && askedChannelIds.add(it) }
        if (missing.isNotEmpty()) {
            val found = account?.fetchBuzzChannelMetadata(relayUrl, missing).orEmpty()
            if (found.isNotEmpty()) _state.update { it.copy(channelInfo = it.channelInfo + found) }
        }
    }

    private suspend fun searchPeople(query: String, current: Int) {
        val events = account?.searchBuzzPeople(relayUrl, query).orEmpty()
        if (current != generation) return
        _state.update { it.copy(people = buzzSearchPeople(events, account?.pubkey), peopleLoading = false) }
    }

    fun retryMessages() {
        val query = _state.value.query
        if (query.isEmpty()) return
        val current = generation
        _state.update { it.copy(messagesLoading = true, messagesFailed = false) }
        launch { searchMessages(query, current) }
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
