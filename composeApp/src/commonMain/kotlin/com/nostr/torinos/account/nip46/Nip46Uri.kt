package com.nostr.torinos.account.nip46

import com.nostr.torinos.network.normalizeRelayUrl

/** `bunker://<署名器の公開鍵>?relay=…&secret=…` を読んだもの。 */
data class BunkerUri(
    val remoteSignerPubkey: String,
    val relays: List<String>,
    val secret: String?,
) {
    override fun toString(): String = "BunkerUri(remoteSignerPubkey=$remoteSignerPubkey, relays=$relays, secret=***)"
}

/** 貼り付けられた `bunker://` を読む。形式が正しくなければ理由つきで失敗する。 */
fun parseBunkerUri(input: String): Result<BunkerUri> = runCatching {
    val text = input.trim()
    require(text.startsWith("bunker://", ignoreCase = true)) { "bunker:// で始まる文字列を貼り付けてください" }
    val rest = text.substring("bunker://".length)
    val pubkey = rest.substringBefore('?').substringBefore('/').trim().lowercase()
    require(isHex64(pubkey)) { "署名器の公開鍵が正しくありません" }
    val params = parseQuery(rest.substringAfter('?', ""))
    val relays = params.filter { it.first == "relay" }.mapNotNull { normalizeRelayUrl(it.second) }.distinct()
    require(relays.isNotEmpty()) { "接続先のリレーが含まれていません" }
    BunkerUri(
        remoteSignerPubkey = pubkey,
        relays = relays,
        secret = params.firstOrNull { it.first == "secret" }?.second?.takeIf { it.isNotBlank() },
    )
}

/** ToriNos から署名器へ接続を頼む `nostrconnect://`。 */
fun buildNostrConnectUri(
    clientPubkey: String,
    relays: List<String>,
    secret: String,
    permissions: String,
    name: String = "ToriNos",
): String = buildString {
    append("nostrconnect://").append(clientPubkey).append('?')
    val params = relays.map { "relay" to it } + listOf(
        "secret" to secret,
        "perms" to permissions,
        "name" to name,
    )
    append(params.joinToString("&") { (key, value) -> "$key=${percentEncode(value)}" })
}

internal fun isHex64(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

private fun parseQuery(query: String): List<Pair<String, String>> =
    query.split('&').filter { it.isNotBlank() }.map { part ->
        percentDecode(part.substringBefore('=')) to percentDecode(part.substringAfter('=', ""))
    }

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

private fun percentEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val c = (byte.toInt() and 0xff).toChar()
        if (c in UNRESERVED) append(c) else append('%').append(((byte.toInt() and 0xff) or 0x100).toString(16).substring(1).uppercase())
    }
}

private fun percentDecode(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '%' && i + 2 < value.length -> {
                val hex = value.substring(i + 1, i + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    bytes += decoded.toByte()
                    i += 3
                    continue
                }
                bytes += c.code.toByte()
            }
            c == '+' -> bytes += ' '.code.toByte()
            else -> c.toString().encodeToByteArray().forEach { bytes += it }
        }
        i++
    }
    return bytes.toByteArray().decodeToString()
}
