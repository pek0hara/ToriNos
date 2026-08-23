package com.nostr.torinos.network

import kotlinx.coroutines.flow.StateFlow

class NgWordStore internal constructor(private val store: PrivateMuteListStore) {
    val ngWords: StateFlow<List<String>> = store.ngWords
    val syncState: StateFlow<PrivateMuteListSyncState> = store.syncState

    fun add(word: String) {
        store.addNgWord(word)
    }

    fun remove(word: String) {
        store.removeNgWord(word)
    }

    fun matches(content: String): Boolean =
        store.matchesNgWord(content)

    fun refresh() {
        store.refresh()
    }
}
