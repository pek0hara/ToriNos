package com.nostr.torinos.ui.thread

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadStoreTest {
    @Test
    fun dispatchIsTheOnlyStateWritePath() {
        val store = ThreadStore()

        store.dispatch(ThreadAction.ReduceState { state ->
            state.copy(replyText = "reply", isLoading = false)
        })

        assertEquals("reply", store.state.value.replyText)
        assertEquals(false, store.state.value.isLoading)
    }

    @Test
    fun reducerIsDeterministicForTheSameStateAndAction() {
        val initial = ThreadViewModel.UiState(replyText = "before")
        val action = ThreadAction.ReduceState { it.copy(replyText = "after") }

        assertEquals(
            ThreadReducer.reduce(initial, action),
            ThreadReducer.reduce(initial, action),
        )
    }
}
