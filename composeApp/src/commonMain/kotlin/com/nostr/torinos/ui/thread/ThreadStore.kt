package com.nostr.torinos.ui.thread

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal sealed interface ThreadAction {
    data class ReplaceState(val state: ThreadViewModel.UiState) : ThreadAction
    data class ReduceState(
        val transform: (ThreadViewModel.UiState) -> ThreadViewModel.UiState,
    ) : ThreadAction
}

internal object ThreadReducer {
    fun reduce(
        state: ThreadViewModel.UiState,
        action: ThreadAction,
    ): ThreadViewModel.UiState = when (action) {
        is ThreadAction.ReplaceState -> action.state
        is ThreadAction.ReduceState -> action.transform(state)
    }
}

internal class ThreadStore(
    initialState: ThreadViewModel.UiState = ThreadViewModel.UiState(),
    private val reducer: ThreadReducer = ThreadReducer,
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<ThreadViewModel.UiState> = mutableState.asStateFlow()

    var value: ThreadViewModel.UiState
        get() = state.value
        set(value) {
            dispatch(ThreadAction.ReplaceState(value))
        }

    fun dispatch(action: ThreadAction) {
        mutableState.update { reducer.reduce(it, action) }
    }
}
