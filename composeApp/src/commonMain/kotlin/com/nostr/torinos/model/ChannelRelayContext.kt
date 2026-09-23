package com.nostr.torinos.model

import com.nostr.torinos.network.normalizeRelayUrl
import com.nostr.torinos.network.normalizeRelayUrls

data class ChannelRelayContext(
    val recommendedRelays: List<String>,
    val readRelays: Set<String>,
    val writeRelays: Set<String>,
    val primaryHint: String?,
) {
    companion object {
        val EMPTY = ChannelRelayContext(emptyList(), emptySet(), emptySet(), null)
    }
}

internal object ChannelRelayContextBuilder {
    fun build(
        recommendedRelays: List<String>,
        navigationRelayHint: String?,
        userReadRelays: Collection<String>,
        userWriteRelays: Collection<String>,
        nonWritableRelays: Collection<String> = emptyList(),
    ): ChannelRelayContext {
        val recommended = normalizeRelayUrls(recommendedRelays)
        val navigation = navigationRelayHint?.let(::normalizeRelayUrl)
        val bootstrapReads = navigation?.let(::listOf) ?: userReadRelays
        val reads = normalizeRelayUrls(recommended + bootstrapReads)
        val blockedWrites = normalizeRelayUrls(nonWritableRelays).toSet()
        val writes = normalizeRelayUrls(recommended + userWriteRelays, limit = Int.MAX_VALUE)
            .filterNot { it in blockedWrites }
            .take(MAX_CONTEXT_RELAYS)
        val normalizedUserWrites = normalizeRelayUrls(userWriteRelays)

        return ChannelRelayContext(
            recommendedRelays = recommended,
            readRelays = reads.toCollection(linkedSetOf()),
            writeRelays = writes.toCollection(linkedSetOf()),
            primaryHint = recommended.firstOrNull()
                ?: navigation
                ?: normalizedUserWrites.firstOrNull(),
        )
    }
}

private const val MAX_CONTEXT_RELAYS = 10
