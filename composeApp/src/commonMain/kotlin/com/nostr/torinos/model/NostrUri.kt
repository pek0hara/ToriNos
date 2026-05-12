package com.nostr.torinos.model

import com.nostr.torinos.crypto.Bech32
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.toHex

data class NostrEventReference(
    val eventId: String,
    val relayUrls: List<String> = emptyList(),
    val authorPubkey: String? = null,
)

data class NostrProfileReference(
    val npub: String,
    val pubkey: String,
    val start: Int = 0,
    val endExclusive: Int = npub.length,
)

private val nostrUriRegex = Regex(
    pattern = """\bnostr:([a-z0-9]+1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)""",
    option = RegexOption.IGNORE_CASE,
)

private val npubReferenceRegex = Regex(
    pattern = """\b(?:nostr:)?(npub1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)""",
    option = RegexOption.IGNORE_CASE,
)

fun extractNpubReferences(text: String): List<NostrProfileReference> =
    npubReferenceRegex.findAll(text)
        .mapNotNull { match ->
            val npub = match.groupValues[1]
            decodeNpub(npub)?.let { pubkey ->
                NostrProfileReference(
                    npub = npub,
                    pubkey = pubkey,
                    start = match.range.first,
                    endExclusive = match.range.last + 1,
                )
            }
        }
        .toList()

fun extractNostrEventReferences(text: String): List<NostrEventReference> =
    nostrUriRegex.findAll(text)
        .mapNotNull { decodeNostrEventReference(it.groupValues[1]) }
        .distinctBy { it.eventId }
        .toList()

fun stripNostrEventUris(text: String): String =
    nostrUriRegex.replace(text) { match ->
        if (decodeNostrEventReference(match.groupValues[1]) != null) "" else match.value
    }.trim()

fun NostrEvent.replyTargetId(): String? {
    val eTags = tags.filter { it.firstOrNull() == "e" }
    if (eTags.isEmpty()) return null
    return eTags.firstOrNull { it.getOrNull(3) == "reply" }?.getOrNull(1)
        ?: eTags.lastOrNull()?.getOrNull(1)
}

fun NostrEvent.channelRootId(): String? {
    val eTags = tags.filter { it.firstOrNull() == "e" }
    return eTags.firstOrNull { it.getOrNull(3) == "root" }?.getOrNull(1)
        ?: eTags.firstOrNull()?.getOrNull(1)
}

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

fun decodeNpub(bech32: String): String? = runCatching {
    val (hrp, bytes) = Bech32.decode(bech32)
    require(hrp == "npub") { "npub payload expected" }
    require(bytes.size == 32) { "npub payload must be 32 bytes" }
    bytes.toHex()
}.getOrNull()

fun encodeNevent(
    eventId: String,
    relayUrls: List<String> = emptyList(),
    authorPubkey: String? = null,
): String {
    val eventIdBytes = eventId.fromHex()
    require(eventIdBytes.size == 32) { "event id must be 32 bytes" }

    val authorPubkeyBytes = authorPubkey?.fromHex()?.also {
        require(it.size == 32) { "author pubkey must be 32 bytes" }
    }

    val tlv = buildList {
        addTlv(type = 0, value = eventIdBytes)
        relayUrls.distinct().forEach { relayUrl ->
            addTlv(type = 1, value = relayUrl.encodeToByteArray())
        }
        if (authorPubkeyBytes != null) {
            addTlv(type = 2, value = authorPubkeyBytes)
        }
    }.toByteArray()

    return Bech32.encode("nevent", tlv)
}

private fun MutableList<Byte>.addTlv(type: Int, value: ByteArray) {
    require(type in 0..255) { "TLV type out of range: $type" }
    require(value.size <= 255) { "TLV value too long: ${value.size}" }
    add(type.toByte())
    add(value.size.toByte())
    addAll(value.toList())
}

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
