package com.nostr.torinos.model

import com.nostr.torinos.emoji.customEmojiMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class NostrProfile(
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val about: String? = null,
    val nip05: String? = null,
    /** Lightning アドレス（LUD-16、`name@domain`）。Zap の受け取り先。 */
    val lud16: String? = null,
    /** bech32 の LNURL（LUD-06）。`lud16` がないときの Zap の受け取り先。 */
    val lud06: String? = null,
    val customEmojis: Map<String, String> = emptyMap(),
) {
    /** 表示名。display_name → name → null の優先順 */
    val bestName: String?
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
}

/**
 * kind:0 イベントの content から NostrProfile をパース。content が JSON オブジェクトでなければ null。
 *
 * 項目ごとに読み、文字列でない項目（配列・数値など）だけを「なし」にする。版ごと捨てると、NIP-01 で置き換えられた
 * 前の版がキャッシュに残り、古い Lightning アドレスへ Zap を送ったり、名前が出なくなったりするため。
 */
fun NostrEvent.toProfile(): NostrProfile? {
    val fields = try {
        Json.parseToJsonElement(content) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return null
    fun text(key: String): String? = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return NostrProfile(
        name = text("name"),
        displayName = text("display_name"),
        picture = text("picture"),
        banner = text("banner"),
        about = text("about"),
        nip05 = text("nip05"),
        lud16 = text("lud16"),
        lud06 = text("lud06"),
        customEmojis = tags.customEmojiMap(),
    )
}

