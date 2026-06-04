package com.nostr.torinos.network

import kotlinx.coroutines.flow.StateFlow

object NgWordStore {
    val ngWords: StateFlow<List<String>> = PrivateMuteListStore.ngWords
    val syncState: StateFlow<PrivateMuteListSyncState> = PrivateMuteListStore.syncState

    fun add(word: String) {
        PrivateMuteListStore.addNgWord(word)
    }

    fun remove(word: String) {
        PrivateMuteListStore.removeNgWord(word)
    }

    fun matches(content: String): Boolean =
        PrivateMuteListStore.matchesNgWord(content)

    fun refresh() {
        PrivateMuteListStore.refresh()
    }
}
