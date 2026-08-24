package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent

/** Channelメッセージの重複排除と新しい順の整列を行う純粋Reducer。 */
internal object ChannelMessageReducer {
    fun received(messages: List<NostrEvent>, event: NostrEvent): List<NostrEvent> {
        if (messages.any { it.id == event.id }) return messages
        return (messages + event).sortedWith(
            compareByDescending<NostrEvent> { it.createdAt }.thenByDescending { it.id },
        )
    }
}
