package com.nostr.torinos.model

internal object ChannelEventTags {
    fun metadata(
        channelId: String,
        relayHint: String?,
        categories: List<String>,
    ): List<List<String>> = buildList {
        add(markedEventTag(channelId, relayHint, "root"))
        categories.map(String::trim).filter(String::isNotBlank).distinct().forEach { category ->
            add(listOf("t", category))
        }
    }

    fun rootMessage(channelId: String, relayHint: String?): List<List<String>> =
        listOf(markedEventTag(channelId, relayHint, "root"))

    fun replyMessage(
        channelId: String,
        channelRelayHint: String?,
        parent: ReplyEventReference,
    ): List<List<String>> = buildList {
        add(markedEventTag(channelId, channelRelayHint, "root"))
        add(markedEventTag(parent.id, parent.relayUrl ?: channelRelayHint, "reply"))
        add(
            buildList {
                add("p")
                add(parent.pubkey)
                parent.pubkeyRelayUrl?.takeIf(String::isNotBlank)?.let(::add)
            },
        )
    }

    private fun markedEventTag(id: String, relayHint: String?, marker: String): List<String> =
        listOf("e", id, relayHint.orEmpty(), marker)
}
