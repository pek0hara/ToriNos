package com.nostr.torinos.crypto

import com.nostr.torinos.model.NostrEvent
import kotlin.time.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray

private val signingJson = Json

data class KeyPair(
    val privateKeyHex: String,
    val publicKeyHex: String,
)

fun generateKeyPair(): KeyPair {
    val priv = generatePrivateKey()
    val pub = derivePublicKey(priv)
    return KeyPair(priv.toHex(), pub.toHex())
}

/**
 * NIP-01 に従いイベントを署名して [NostrEvent] を返す。
 * tags: e.g. listOf(listOf("e", replyToId), listOf("p", pubkey))
 */
fun signEvent(
    privateKeyHex: String,
    content: String,
    kind: Int = 1,
    tags: List<List<String>> = emptyList(),
    createdAt: Long = Clock.System.now().epochSeconds,
): NostrEvent {
    val privateKey = privateKeyHex.fromHex()
    val publicKey = derivePublicKey(privateKey)
    val pubkeyHex = publicKey.toHex()

    // NIP-01: [0, pubkey, created_at, kind, tags, content]
    val serialized = buildJsonArray {
        add(0)
        add(pubkeyHex)
        add(createdAt)
        add(kind)
        add(buildJsonArray {
            tags.forEach { tag ->
                addJsonArray { tag.forEach { add(it) } }
            }
        })
        add(content)
    }.let { signingJson.encodeToString(it) }

    val eventId = sha256(serialized.encodeToByteArray()).toHex()
    val sig = schnorrSign(eventId.fromHex(), privateKey).toHex()

    return NostrEvent(
        id = eventId,
        pubkey = pubkeyHex,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = sig,
    )
}

fun calculateEventId(event: NostrEvent): String =
    sha256(serializedEventPayload(event).encodeToByteArray()).toHex()

fun isValidEvent(event: NostrEvent): Boolean = runCatching {
    event.id.length == 64 &&
        event.pubkey.length == 64 &&
        event.sig.length == 128 &&
        event.id == calculateEventId(event) &&
        schnorrVerify(event.sig.fromHex(), event.id.fromHex(), event.pubkey.fromHex())
}.getOrDefault(false)

private fun serializedEventPayload(event: NostrEvent): String =
    buildJsonArray {
        add(0)
        add(event.pubkey)
        add(event.createdAt)
        add(event.kind)
        add(buildJsonArray {
            event.tags.forEach { tag ->
                addJsonArray { tag.forEach { add(it) } }
            }
        })
        add(event.content)
    }.let { signingJson.encodeToString(it) }
