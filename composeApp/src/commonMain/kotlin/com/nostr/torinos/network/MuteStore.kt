package com.nostr.torinos.network

import kotlinx.coroutines.flow.StateFlow

class MuteStore internal constructor(private val store: PrivateMuteListStore) {
    val mutedPubkeys: StateFlow<Set<String>> = store.mutedPubkeys
    val syncState: StateFlow<PrivateMuteListSyncState> = store.syncState

    fun mute(pubkey: String) {
        store.mute(pubkey)
    }

    fun unmute(pubkey: String) {
        store.unmute(pubkey)
    }

    fun isMuted(pubkey: String): Boolean =
        store.isMuted(pubkey)

    fun refresh() {
        store.refresh()
    }
}
