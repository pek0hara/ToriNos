package com.nostr.torinos.article

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class SelectedMatometrPost(val event: NostrEvent, val reference: MatometrReference)

/** Quote the reply itself; its root/parent tags do not change the selected event. */
internal fun NostrEvent.canSelectForMatometr(): Boolean = kind == 1 || kind == COMMENT_EVENT_KIND
internal data class MatometrSelectionState(
    val active: Boolean = false,
    val posts: List<SelectedMatometrPost> = emptyList(),
    val busy: Boolean = false,
    val committedId: String? = null,
    val handoffId: String = articleLocalId(),
    val targetDraftId: String? = null,
    val targetRevision: Long? = null,
    val targetTitle: String = "",
    val error: String? = null,
)
/** One coordinator per logged-in account; resolved public notes and comments only. */
internal class MatometrSelectionCoordinator(private val canEdit: () -> Boolean = { true }) {
    private val mutable = MutableStateFlow(MatometrSelectionState())
    val state = mutable.asStateFlow()
    fun start(targetDraftId: String? = null, targetRevision: Long? = null, targetTitle: String = "") {
        if (!canEdit() || mutable.value.busy) return
        mutable.value = if (targetDraftId == null) mutable.value.copy(active = true)
            else MatometrSelectionState(active = true, targetDraftId = targetDraftId, targetRevision = targetRevision, targetTitle = targetTitle)
    }
    fun hide() { if (canEdit() && !mutable.value.busy) mutable.update { it.copy(active = false) } }
    fun toggle(event: NostrEvent, relayHints: List<String> = emptyList()) {
        if (!canEdit() || mutable.value.busy || mutable.value.committedId != null || !event.canSelectForMatometr()) return
        if (!mutable.value.active) start()
        mutable.update { s ->
            if (s.posts.any { it.event.id == event.id }) s.copy(posts = s.posts.filterNot { it.event.id == event.id }, error = null)
            else if (s.posts.size >= 50) s.copy(error = "選択できる投稿は50件までです")
            else s.copy(posts = s.posts + SelectedMatometrPost(event, MatometrReference(event.id, event.pubkey, relayHints)), error = null)
        }
    }
    fun remove(id: String) { if (canEdit() && !mutable.value.busy && mutable.value.committedId == null) mutable.update { it.copy(posts = it.posts.filterNot { p -> p.event.id == id }) } }
    fun move(id: String, delta: Int) {
        if (!canEdit() || mutable.value.busy || mutable.value.committedId != null) return
        mutable.update { s ->
            val posts = s.posts.toMutableList()
            val index = posts.indexOfFirst { it.event.id == id }
            val destination = index + delta
            if (index < 0 || destination !in posts.indices) s else {
                posts.add(destination, posts.removeAt(index)); s.copy(posts = posts)
            }
        }
    }
    fun clear() { if (canEdit() && !mutable.value.busy && mutable.value.committedId == null) mutable.update { it.copy(posts = emptyList(), error = null) } }
    fun cancel() { if (canEdit() && !mutable.value.busy) mutable.value = MatometrSelectionState() }
    fun beginHandoff(): MatometrSelectionState? {
        val s = mutable.value
        if (!canEdit() || !s.active || s.busy || s.posts.isEmpty()) return null
        mutable.value = s.copy(busy = true, error = null)
        return s
    }
    fun markCommitted(id: String) { mutable.update { it.copy(committedId = id) } }
    fun finishHandoff() { mutable.value = MatometrSelectionState() }
    fun failHandoff(message: String) { mutable.update { it.copy(busy = false, error = message) } }
}
