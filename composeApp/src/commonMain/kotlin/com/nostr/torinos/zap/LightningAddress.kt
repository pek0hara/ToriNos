package com.nostr.torinos.zap

import com.nostr.torinos.crypto.Bech32
import com.nostr.torinos.model.NostrProfile

/**
 * Zap の受け取り先。LNURL-pay の URL と、Zap リクエストの `lnurl` タグに入れる bech32。
 */
data class ZapRecipientAddress(
    /** LNURL-pay の応答を取りに行く URL（https）。 */
    val url: String,
) {
    /** LUD-01 の bech32 `lnurl`（小文字）。 */
    val lnurl: String get() = encodeLnurl(url)
}

private val LUD16_NAME = Regex("[a-z0-9\\-_.+]+")
private val DOMAIN_LABEL = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")

/**
 * `lud16`（`name@domain`）を LNURL-pay の URL にする（LUD-16）。形式が正しくなければ null。
 * `.onion` は https で接続できないため対象外。IP アドレスも対象外。
 */
fun lud16ToUrl(lud16: String): String? {
    val parts = lud16.trim().split('@')
    if (parts.size != 2) return null
    val name = parts[0].lowercase()
    val domain = parts[1].lowercase()
    if (name.isEmpty() || !name.matches(LUD16_NAME)) return null
    if (domain.length > 253 || !domain.contains('.') || domain.endsWith(".onion")) return null
    val labels = domain.split('.')
    if (labels.any { it.length !in 1..63 || !it.matches(DOMAIN_LABEL) }) return null
    // IP アドレス（192.168.1.1 など）は対象外。他人のプロフィールを見ただけで LAN の中へ接続させないため。
    if (labels.all { label -> label.all(Char::isDigit) }) return null
    return "https://$domain/.well-known/lnurlp/$name"
}

/** `lud06`（bech32 の `lnurl`）を URL にする。https 以外と解読できないものは null。 */
fun decodeLnurl(lnurl: String): String? = runCatching {
    val trimmed = lnurl.trim().removePrefix("lightning:").removePrefix("LIGHTNING:")
    val (hrp, words) = Bech32.decodeWords(trimmed)
    if (hrp != "lnurl") return null
    Bech32.wordsToBytes(words).decodeToString()
}.getOrNull()?.takeIf { it.startsWith("https://") }

/** URL を LUD-01 の bech32 `lnurl` にする。 */
fun encodeLnurl(url: String): String = Bech32.encode("lnurl", url.encodeToByteArray())

/** プロフィールの `lud16` を優先し、なければ `lud06` から受け取り先を決める。 */
fun NostrProfile.zapRecipientAddress(): ZapRecipientAddress? {
    lud16?.takeIf { it.isNotBlank() }?.let(::lud16ToUrl)?.let { return ZapRecipientAddress(it) }
    return lud06?.takeIf { it.isNotBlank() }?.let(::decodeLnurl)?.let(::ZapRecipientAddress)
}
