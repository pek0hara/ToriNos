package com.nostr.torinos.status

import com.nostr.torinos.emoji.customEmojiMap
import com.nostr.torinos.emoji.customEmojiTagsForContent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.ui.components.extractWebUrls

object StatusEventCodec {
    fun parse(event: NostrEvent): StatusEntry? {
        if (event.kind != STATUS_EVENT_KIND) return null
        val identifier = event.tags
            .firstOrNull { it.firstOrNull() == "d" }
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: GENERAL_STATUS_IDENTIFIER
        val expiration = event.tags
            .firstOrNull { it.firstOrNull() == "expiration" }
            ?.getOrNull(1)
            ?.toLongOrNull()
        val referenceUrls = event.tags
            .asSequence()
            .filter { it.firstOrNull() == "r" }
            .mapNotNull { it.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .toList()
        return StatusEntry(
            event = event,
            address = StatusAddress(event.pubkey, identifier),
            content = event.content,
            expiration = expiration,
            referenceUrls = referenceUrls,
            customEmojis = event.tags.customEmojiMap(),
        )
    }

    fun buildTags(
        identifier: String,
        content: String,
        expiration: Long?,
        explicitReferenceUrl: String?,
        customEmojis: List<CustomEmoji>,
    ): List<List<String>> = buildList {
        add(listOf("d", identifier.trim().ifBlank { GENERAL_STATUS_IDENTIFIER }))
        if (expiration != null) add(listOf("expiration", expiration.toString()))
        addAll(customEmojiTagsForContent(content, customEmojis))
        val explicitUrl = explicitReferenceUrl?.trim()?.takeIf(String::isNotBlank)
        (listOfNotNull(explicitUrl) + extractWebUrls(content)).distinct().forEach { url ->
            add(listOf("r", url))
        }
    }
}
