package com.nostr.torinos.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass
import kotlin.time.Clock

/** 検索画面の状態を画面の寿命に合わせて保持する。処理は [SearchController] に委ねる。 */
class SearchViewModel(accountSession: com.nostr.torinos.account.AccountSession? = null) : ViewModel() {
    private val controller = SearchController(
        scope = viewModelScope,
        sessionId = accountSession?.sessionId,
        ownPubkey = accountSession?.pubkey,
        nowEpochSeconds = { Clock.System.now().epochSeconds },
    )

    private var appliedInitialQuery: String? = null

    internal val state: StateFlow<SearchUiState> get() = controller.state
    internal val posts: StateFlow<PostSearchUiState> get() = controller.posts.state

    /** 初期クエリを初めて受け取ったときだけ true。画面の作り直しで同じクエリを再適用しない。 */
    fun markInitialQueryApplied(query: String): Boolean {
        if (appliedInitialQuery == query) return false
        appliedInitialQuery = query
        return true
    }

    fun search(query: String, tab: SearchTab) = controller.search(query, tab)
    fun selectTab(tab: SearchTab) = controller.selectTab(tab)
    fun loadMore() = controller.loadMore()
    fun retryPosts() = controller.retryPosts()
    fun retryUsers() = controller.retryUsers()
    fun stop() = controller.stop()
    fun resume() = controller.resume()

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                SearchViewModel() as T
        }
    }
}
