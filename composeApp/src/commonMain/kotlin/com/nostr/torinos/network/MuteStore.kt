package com.nostr.torinos.network

import kotlinx.coroutines.flow.StateFlow

object MuteStore {
    val mutedPubkeys: StateFlow<Set<String>> = PrivateMuteListStore.mutedPubkeys
    val syncState: StateFlow<PrivateMuteListSyncState> = PrivateMuteListStore.syncState

    fun mute(pubkey: String) {
        PrivateMuteListStore.mute(pubkey)
    }

    fun unmute(pubkey: String) {
        PrivateMuteListStore.unmute(pubkey)
    }

    fun isMuted(pubkey: String): Boolean =
        PrivateMuteListStore.isMuted(pubkey)

    fun refresh() {
        PrivateMuteListStore.refresh()
    }
}
