package com.nostr.torinos.ui.timeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** I/Oを持たず、画面Stateの唯一の書き込み先となるStore。 */
internal class StateStore<S>(initialState: S) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<S> = mutableState.asStateFlow()

    var value: S
        get() = state.value
        set(value) {
            mutableState.value = value
        }

    fun dispatch(reducer: (S) -> S) {
        mutableState.update(reducer)
    }
}
