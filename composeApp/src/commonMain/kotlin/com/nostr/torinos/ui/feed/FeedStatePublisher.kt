package com.nostr.torinos.ui.feed

import com.nostr.torinos.ui.SafeCoroutineLauncher
import com.nostr.torinos.ui.timeline.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** 同じディスパッチャ上で、内部の最新状態とUIへの間引き公開を管理する。 */
internal class FeedStatePublisher(scope: CoroutineScope, private val onPublished: (FeedViewModel.UiState) -> Unit = {}) {
    private val launcher = SafeCoroutineLauncher(scope, "FeedStatePublisher")
    private val store = StateStore(FeedViewModel.UiState())
    val state = store.state
    var current = FeedViewModel.UiState()
        private set
    var revision = 0L
        private set
    private var pending: Job? = null
    private var closed = false

    fun invalidate() { revision++ }
    fun set(value: FeedViewModel.UiState, immediate: Boolean = true) {
        if (closed) return
        current = value
        invalidate()
        if (immediate) publish() else if (pending?.isActive != true) {
            pending = launcher.launch { delay(150); publish() }
        }
    }

    fun cancelPending() { pending?.cancel(); pending = null }
    fun publish() {
        cancelPending()
        if (closed) return
        store.value = current
        onPublished(current)
    }
    fun close() { closed = true; cancelPending() }
}
