package com.nostr.torinos.ui.thread

import com.nostr.torinos.model.NostrEvent

internal data class ThreadTreeState(
    val root: NostrEvent? = null,
    val replies: List<NostrEvent> = emptyList(),
    val repliesByEventId: Map<String, List<NostrEvent>> = emptyMap(),
    val replyCounts: Map<String, Int> = emptyMap(),
)

internal sealed interface ThreadTreeAction {
    data class RootReceived(val event: NostrEvent) : ThreadTreeAction
    data class DirectReplyReceived(val event: NostrEvent) : ThreadTreeAction
    data class DescendantReplyReceived(
        val targetId: String,
        val event: NostrEvent,
    ) : ThreadTreeAction

    data class ReplyPublished(
        val rootId: String,
        val event: NostrEvent,
    ) : ThreadTreeAction
}

internal object ThreadTreeReducer {
    fun reduce(state: ThreadTreeState, action: ThreadTreeAction): ThreadTreeState = when (action) {
        is ThreadTreeAction.RootReceived -> {
            if (state.root == action.event) state else state.copy(root = action.event)
        }

        is ThreadTreeAction.DirectReplyReceived -> {
            if (state.replies.any { it.id == action.event.id }) {
                state
            } else {
                state.copy(replies = (state.replies + action.event).sortedByEventOrder())
            }
        }

        is ThreadTreeAction.DescendantReplyReceived -> {
            val current = state.repliesByEventId[action.targetId].orEmpty()
            if (current.any { it.id == action.event.id }) {
                state
            } else {
                state.copy(
                    repliesByEventId = state.repliesByEventId +
                        (action.targetId to (current + action.event).sortedByEventOrder()),
                    replyCounts = state.replyCounts +
                        (action.targetId to (state.replyCounts[action.targetId] ?: 0) + 1),
                )
            }
        }

        is ThreadTreeAction.ReplyPublished -> {
            if (state.replies.any { it.id == action.event.id }) {
                state
            } else {
                state.copy(
                    replies = (state.replies + action.event).sortedByEventOrder(),
                    replyCounts = state.replyCounts +
                        (action.rootId to (state.replyCounts[action.rootId] ?: state.replies.size) + 1),
                )
            }
        }
    }
}

private fun List<NostrEvent>.sortedByEventOrder(): List<NostrEvent> =
    sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })

internal fun ThreadViewModel.UiState.threadTreeState(): ThreadTreeState = ThreadTreeState(
    root = root,
    replies = replies,
    repliesByEventId = repliesByEventId,
    replyCounts = replyCounts,
)

internal fun ThreadViewModel.UiState.withThreadTree(tree: ThreadTreeState): ThreadViewModel.UiState = copy(
    root = tree.root,
    replies = tree.replies,
    repliesByEventId = tree.repliesByEventId,
    replyCounts = tree.replyCounts,
)
