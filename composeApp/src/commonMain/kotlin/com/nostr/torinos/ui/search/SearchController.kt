package com.nostr.torinos.ui.search

import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.SafeCoroutineLauncher
import com.nostr.torinos.util.networkTraceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SearchTab {
    Posts,
    Users,
}

enum class SearchLoadState {
    NotRequested,
    Loading,
    Interrupted,
    Ready,
    Failed,
}

internal data class SearchUiState(
    val query: String = "",
    val selectedTab: SearchTab = SearchTab.Posts,
    val usersLoadState: SearchLoadState = SearchLoadState.NotRequested,
    val users: List<Pair<String, NostrProfile>> = emptyList(),
)

/**
 * 検索語と選択タブを持ち、選択中のタブに対応する検索だけを開始する。
 * 投稿検索のページ取得と付随情報は [PostSearchController] が所有する。
 */
internal class SearchController(
    scope: CoroutineScope,
    private val gateway: SearchGateway = RepositorySearchGateway,
    private val config: SearchConfig = SearchConfig(),
    nowEpochSeconds: () -> Long,
    ownerId: String = newSearchOwnerId(),
) {
    private val launcher = SafeCoroutineLauncher(scope, "SearchController")
    val posts = PostSearchController(scope, gateway, config, nowEpochSeconds, ownerId)

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var generation = 0
    private var userSearchJob: Job? = null

    fun search(query: String, tab: SearchTab = _state.value.selectedTab) {
        val trimmed = query.trim()
        networkTraceLog { "[Search] search() called: query='$trimmed' tab=$tab" }
        if (trimmed.isBlank()) return
        generation++
        userSearchJob?.cancel()
        userSearchJob = null
        _state.value = SearchUiState(query = trimmed, selectedTab = tab)
        posts.reset(trimmed)
        startSelectedTab()
    }

    /** 未取得または中断したタブだけ検索を開始する。取得済みのタブへ戻っただけでは再検索しない。 */
    fun selectTab(tab: SearchTab) {
        if (_state.value.selectedTab != tab) _state.value = _state.value.copy(selectedTab = tab)
        startSelectedTab()
    }

    fun loadMore() = posts.loadMore()

    fun retryPosts() = posts.retry()

    fun retryUsers() {
        if (_state.value.usersLoadState == SearchLoadState.Failed) startUserSearch()
    }

    /** 画面が非表示になった時点で、検索画面が所有する通信と保留処理を止める。 */
    fun stop() {
        posts.stop()
        if (userSearchJob?.isActive == true) {
            userSearchJob?.cancel()
            userSearchJob = null
            _state.value = _state.value.copy(usersLoadState = SearchLoadState.Interrupted)
        }
    }

    /** 画面復帰時、選択中のタブで中断した取得だけを再開する。 */
    fun resume() {
        posts.resume()
        when (_state.value.selectedTab) {
            SearchTab.Posts -> if (posts.pageState is PostPageState.Interrupted) posts.ensureStarted()
            SearchTab.Users -> if (_state.value.usersLoadState == SearchLoadState.Interrupted) startUserSearch()
        }
    }

    private fun startSelectedTab() {
        if (_state.value.query.isBlank()) return
        when (_state.value.selectedTab) {
            SearchTab.Posts -> posts.ensureStarted()
            SearchTab.Users -> if (_state.value.usersLoadState in RESTARTABLE_USER_STATES) startUserSearch()
        }
    }

    private fun startUserSearch() {
        val query = _state.value.query
        if (query.isBlank() || userSearchJob?.isActive == true) return
        val generation = generation
        _state.value = _state.value.copy(usersLoadState = SearchLoadState.Loading)
        userSearchJob = launcher.launch {
            val result = try {
                gateway.searchUsers(query, config.searchRelayUrl, config.userSearchLimit)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (generation != this@SearchController.generation) return@launch
            userSearchJob = null
            _state.value = if (result == null) {
                _state.value.copy(usersLoadState = SearchLoadState.Failed)
            } else {
                _state.value.copy(usersLoadState = SearchLoadState.Ready, users = result.distinctBy { it.first })
            }
        }
    }

    private companion object {
        val RESTARTABLE_USER_STATES = setOf(SearchLoadState.NotRequested, SearchLoadState.Interrupted)
    }
}
