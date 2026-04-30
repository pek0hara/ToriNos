package com.nostr.torinos.model

import com.nostr.torinos.crypto.Bech32
import com.nostr.torinos.crypto.toHex

data class NostrEventReference(
    val eventId: String,
    val relayUrls: List<String> = emptyList(),
    val authorPubkey: String? = null,
)

private val nostrUriRegex = Regex(
    pattern = """\bnostr:([a-z0-9]+1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)""",
    option = RegexOption.IGNORE_CASE,
)

fun extractNostrEventReferences(text: String): List<NostrEventReference> =
    nostrUriRegex.findAll(text)
        .mapNotNull { decodeNostrEventReference(it.groupValues[1]) }
        .distinctBy { it.eventId }
        .toList()

fun stripNostrEventUris(text: String): String =
    nostrUriRegex.replace(text) { match ->
        if (decodeNostrEventReference(match.groupValues[1]) != null) "" else match.value
    }.trim()

fun quotedEventIds(event: NostrEvent): List<String> {
    val qTagIds = event.tags
        .filter { it.firstOrNull() == "q" }
        .mapNotNull { it.getOrNull(1) }
    val uriIds = extractNostrEventReferences(event.content).map { it.eventId }
    return (qTagIds + uriIds).distinct()
}

fun decodeNostrEventReference(bech32: String): NostrEventReference? = runCatching {
    val (hrp, bytes) = Bech32.decode(bech32)
    when (hrp) {
        "note" -> {
            require(bytes.size == 32) { "note payload must be 32 bytes" }
            NostrEventReference(eventId = bytes.toHex())
        }
        "nevent" -> decodeNevent(bytes)
        else -> null
    }
}.getOrNull()

private fun decodeNevent(bytes: ByteArray): NostrEventReference? {
    var index = 0
    var eventId: String? = null
    val relays = mutableListOf<String>()
    var authorPubkey: String? = null

    while (index + 2 <= bytes.size) {
        val type = bytes[index].toInt() and 0xFF
        val length = bytes[index + 1].toInt() and 0xFF
        index += 2
        if (index + length > bytes.size) return null
        val value = bytes.copyOfRange(index, index + length)
        index += length

        when (type) {
            0 -> if (length == 32 && eventId == null) eventId = value.toHex()
            1 -> relays += value.decodeToString()
            2 -> if (length == 32 && authorPubkey == null) authorPubkey = value.toHex()
        }
    }

    return eventId?.let {
        NostrEventReference(
            eventId = it,
            relayUrls = relays.distinct(),
            authorPubkey = authorPubkey,
        )
    }
}
