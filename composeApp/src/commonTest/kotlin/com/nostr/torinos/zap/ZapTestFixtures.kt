package com.nostr.torinos.zap

import com.nostr.torinos.crypto.Bech32
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import kotlinx.serialization.json.Json

internal val senderKey = "0".repeat(63) + "1"
internal val zapperKey = "0".repeat(63) + "2"
internal val otherKey = "0".repeat(63) + "3"
internal val authorPubkey = "a".repeat(64)
internal val noteId = "b".repeat(64)

internal fun pubkeyOf(privateKeyHex: String) = derivePublicKey(privateKeyHex.fromHex()).toHex()

/**
 * 指定した hrp・時刻・タグで請求書を作る（署名は 0 埋め。署名は検証しないため）。
 * [tags] はタグの種類（bech32 の値）と 5 ビット単位の値。
 */
internal fun synthesizeInvoice(
    hrp: String,
    timestamp: Long = 1_700_000_000,
    tags: List<Pair<Int, IntArray>> = emptyList(),
): String {
    val words = mutableListOf<Int>()
    for (shift in 6 downTo 0) words += ((timestamp shr (shift * 5)) and 31).toInt()
    tags.forEach { (type, data) ->
        words += type
        words += data.size / 32
        words += data.size % 32
        words += data.toList()
    }
    repeat(104) { words += 0 }
    return Bech32.encodeWords(hrp, words.toIntArray())
}

/** sats の額の請求書（`n` 単位で表す）。 */
internal fun invoiceForMsats(msats: Long, timestamp: Long = 1_700_000_000, expiry: Long? = null): String {
    require(msats % 100 == 0L)
    val tags = buildList {
        if (expiry != null) {
            val words = mutableListOf<Int>()
            var value: Long = expiry
            while (value > 0) { words.add(0, (value and 31).toInt()); value = value shr 5 }
            add(6 to words.toIntArray())
        }
    }
    return synthesizeInvoice("lnbc${msats / 100}n", timestamp, tags)
}

internal fun zapRequest(
    amountMsats: Long? = 21_000,
    recipient: String = authorPubkey,
    eventId: String? = noteId,
    key: String = senderKey,
    content: String = "",
    extraTags: List<List<String>> = emptyList(),
): NostrEvent = signEvent(
    privateKeyHex = key,
    content = content,
    kind = ZAP_REQUEST_KIND,
    tags = buildList {
        add(listOf("relays", "wss://relay.example"))
        amountMsats?.let { add(listOf("amount", it.toString())) }
        add(listOf("p", recipient))
        eventId?.let { add(listOf("e", it)) }
        addAll(extraTags)
    },
    createdAt = 1_700_000_000,
)

internal fun zapReceipt(
    request: NostrEvent = zapRequest(),
    bolt11: String = invoiceForMsats(21_000),
    key: String = zapperKey,
    recipient: String = request.tags.first { it[0] == "p" }[1],
    eventId: String? = request.tags.firstOrNull { it[0] == "e" }?.get(1),
    senderTag: String? = request.pubkey,
    createdAt: Long = 1_700_000_010,
): NostrEvent = signEvent(
    privateKeyHex = key,
    content = "",
    kind = ZAP_RECEIPT_KIND,
    tags = buildList {
        add(listOf("p", recipient))
        eventId?.let { add(listOf("e", it)) }
        senderTag?.let { add(listOf("P", it)) }
        add(listOf("bolt11", bolt11))
        add(listOf("description", Json.encodeToString(NostrEvent.serializer(), request)))
    },
    createdAt = createdAt,
)
