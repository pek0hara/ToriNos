package com.nostr.torinos.chat

import com.nostr.torinos.model.decodeNaddr
import com.nostr.torinos.model.encodeNaddr
import com.nostr.torinos.network.normalizeRelayUrl
import io.ktor.http.decodeURLQueryComponent

// NIP-29 / NIP-C7 の kind。
const val GROUP_CHAT_KIND = 9
const val GROUP_PUT_USER_KIND = 9000
const val GROUP_REMOVE_USER_KIND = 9001
const val GROUP_DELETE_EVENT_KIND = 9005
/** NIP-29: グループの削除（Buzz は owner だけ）。 */
const val GROUP_DELETE_GROUP_KIND = 9008
const val GROUP_JOIN_REQUEST_KIND = 9021
const val GROUP_LEAVE_REQUEST_KIND = 9022
const val GROUP_METADATA_KIND = 39000
const val GROUP_ADMINS_KIND = 39001
const val GROUP_MEMBERS_KIND = 39002
const val SIMPLE_GROUPS_LIST_KIND = 10009
/** 標準の NIP-29 で使われるスレッド（kind 11）とコメント（NIP-22 kind 1111）。 */
const val GROUP_THREAD_KIND = 11
const val GROUP_COMMENT_KIND = 1111
// Buzz 独自の kind。
const val BUZZ_AGENT_MESSAGE_KIND = 40002
const val BUZZ_EDIT_KIND = 40003
const val BUZZ_SYSTEM_MESSAGE_KIND = 40099
const val BUZZ_FORUM_POST_KIND = 45001
const val BUZZ_FORUM_VOTE_KIND = 45002
const val BUZZ_FORUM_COMMENT_KIND = 45003

/** NIP-29 のグループ。リレーとグループIDの組で決まる。[relayUrl] は正規化済み。 */
data class GroupRef(val relayUrl: String, val groupId: String) {
    val key: String get() = "$relayUrl'$groupId"
}

/** チャットタブの「参加する」に貼られたもの。 */
sealed interface GroupInput {
    /** グループを指すもの（kind 39000 の naddr、`host'id`）。 */
    data class Group(val ref: GroupRef, val inviteCode: String?, val relayPubkey: String?) : GroupInput

    /** リレーだけを指すもの。リレーのグループ一覧を開く。 */
    data class Relay(val relayUrl: String) : GroupInput

    /** Buzz の招待（`https://<host>/invite/<code>`、`buzz://join?relay=…&code=…`）。 */
    data class BuzzInvite(val relayUrl: String, val code: String) : GroupInput

    /** Buzz の招待コードだけ。コミュニティが分からないので、リンク全体を貼ってもらう。 */
    data object BareBuzzCode : GroupInput

    data object Invalid : GroupInput
}

private val GROUP_ID_PATTERN = Regex("^[^\\s']+$")
private val BARE_HOST_PATTERN = Regex("^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(:[0-9]{1,5})?/?$")

private val BUZZ_CODE_PATTERN = Regex("^v2\\.[A-Za-z0-9_-]{43}$")
private val INVITE_PATH_PATTERN = Regex("^https://([^/?#@]+)/invite/([^/?#]+)/?$", RegexOption.IGNORE_CASE)

fun parseGroupInput(text: String): GroupInput {
    val value = text.trim().removePrefix("nostr:")
    if (value.isEmpty()) return GroupInput.Invalid
    if (value.startsWith("naddr1", ignoreCase = true)) return parseGroupNaddr(value)
    if (value.startsWith("buzz://", ignoreCase = true)) return parseBuzzInviteLink(value)
    if (isToriNosLinkCandidate(value)) return parseToriNosInviteLink(value) ?: GroupInput.Invalid
    if (BUZZ_CODE_PATTERN.matches(value)) return GroupInput.BareBuzzCode
    INVITE_PATH_PATTERN.matchEntire(value)?.let { match ->
        val relayUrl = relayUrlOf("https://" + match.groupValues[1]) ?: return GroupInput.Invalid
        val code = percentDecode(match.groupValues[2]).takeIf { it.isNotBlank() && it.length <= 256 } ?: return GroupInput.Invalid
        return GroupInput.BuzzInvite(relayUrl, code)
    }
    if (value.contains("/invite/", ignoreCase = true)) return GroupInput.Invalid
    val apostrophe = value.indexOf('\'')
    if (apostrophe >= 0) {
        val relayUrl = relayUrlOf(value.substring(0, apostrophe)) ?: return GroupInput.Invalid
        val groupId = value.substring(apostrophe + 1)
        if (!GROUP_ID_PATTERN.matches(groupId)) return GroupInput.Invalid
        return GroupInput.Group(GroupRef(relayUrl, groupId), inviteCode = null, relayPubkey = null)
    }
    return relayUrlOf(value)?.let(GroupInput::Relay) ?: GroupInput.Invalid
}

/** `naddr1…[?invite=<code>]`。kind 39000 で、リレーとグループIDがあるものだけ。 */
private fun parseGroupNaddr(value: String): GroupInput {
    val bech32 = value.substringBefore('?')
    val query = value.substringAfter('?', missingDelimiterValue = "")
    val address = decodeNaddr(bech32) ?: return GroupInput.Invalid
    if (address.kind != GROUP_METADATA_KIND || address.identifier.isEmpty()) return GroupInput.Invalid
    val relayUrl = address.relayUrls.firstNotNullOfOrNull(::normalizeRelayUrl) ?: return GroupInput.Invalid
    val inviteCode = query.split('&')
        .firstOrNull { it.startsWith("invite=") }
        ?.removePrefix("invite=")
        ?.takeIf { it.isNotEmpty() }
    return GroupInput.Group(GroupRef(relayUrl, address.identifier), inviteCode, address.pubkey)
}

/** `wss://`・`ws://`・`https://`（パス無し）・ホスト名を、WebSocket の URL にする。 */
internal fun relayUrlOf(value: String): String? {
    val trimmed = value.trim()
    val candidate = when {
        trimmed.startsWith("wss://", ignoreCase = true) || trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
        trimmed.startsWith("https://", ignoreCase = true) -> {
            val rest = trimmed.substring("https://".length)
            if (rest.trimEnd('/').contains('/')) return null
            "wss://$rest"
        }
        BARE_HOST_PATTERN.matches(trimmed) -> "wss://$trimmed"
        else -> return null
    }
    return normalizeRelayUrl(candidate)
}

/** `buzz://join`・`buzz://connect`・`buzz://add-community`（Buzz の `parseInviteLink` と同じ形）。 */
private fun parseBuzzInviteLink(value: String): GroupInput {
    val form = Regex("^buzz://(join|connect|add-community)/?\\?", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.lowercase()
        ?: return GroupInput.Invalid
    val params = queryParams(value.substringAfter('?'))
    val relays = params["relay"].orEmpty()
    if (relays.size != 1) return GroupInput.Invalid
    val relayUrl = relayUrlOf(relays.single()) ?: return GroupInput.Invalid
    if (form != "join") return GroupInput.Relay(relayUrl)
    val codes = params["code"].orEmpty()
    if (codes.size != 1 || codes.single().isBlank() || codes.single().length > 256) return GroupInput.Invalid
    return GroupInput.BuzzInvite(relayUrl, codes.single())
}

internal fun queryParams(query: String): Map<String, List<String>> =
    query.substringBefore('#').split('&').filter { it.isNotEmpty() }.groupBy(
        keySelector = { percentDecode(it.substringBefore('=')) },
        valueTransform = { percentDecode(it.substringAfter('=', missingDelimiterValue = "")) },
    )

internal fun percentDecode(value: String): String =
    runCatching { value.decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(value)

/** メッセージ中のリンクのうち、外へ開いてよいもの（http・https・mailto）。javascript: や intent: は開かない。 */
fun isSafeExternalLink(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https" || scheme == "mailto"
}

/** NIP-29 リレーの種類。Buzz は NIP-11 の `supported_extensions` に `buzz-*` を持つ。 */
enum class GroupFlavor { Standard, Buzz }

fun groupFlavorOf(supportedExtensions: List<String>): GroupFlavor =
    if (supportedExtensions.any { it.startsWith("buzz-") }) GroupFlavor.Buzz else GroupFlavor.Standard

/** Buzz のメッセージ内リンク（コミュニティを含まない。表示中のコミュニティで解釈する）。 */
sealed interface BuzzLink {
    data class Channel(val channelId: String) : BuzzLink
    data class Message(val channelId: String, val messageId: String, val threadRootId: String?) : BuzzLink
}

private val BUZZ_CHANNEL_ID = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,255}$")
private val HEX64_ID = Regex("^[0-9a-fA-F]{64}$")

fun parseBuzzLink(href: String): BuzzLink? {
    if (!href.startsWith("buzz://", ignoreCase = true) || href.length > 4096) return null
    val rest = href.substring("buzz://".length)
    val host = rest.substringBefore('/').substringBefore('?').lowercase()
    return when (host) {
        "channel" -> {
            val parts = rest.substringAfter('/', missingDelimiterValue = "").substringBefore('?').trimEnd('/').split('/')
            val channel = parts.getOrNull(0)?.takeIf(BUZZ_CHANNEL_ID::matches) ?: return null
            when (val message = parts.getOrNull(1)) {
                null -> BuzzLink.Channel(channel)
                else -> if (HEX64_ID.matches(message) && parts.size == 2) BuzzLink.Message(channel, message.lowercase(), null) else null
            }
        }
        "message" -> {
            val params = queryParams(rest.substringAfter('?', missingDelimiterValue = ""))
            val channel = params["channel"]?.singleOrNull()?.takeIf(BUZZ_CHANNEL_ID::matches) ?: return null
            val id = params["id"]?.singleOrNull()?.takeIf(HEX64_ID::matches) ?: return null
            val thread = params["thread"]?.singleOrNull()
            if (thread != null && !HEX64_ID.matches(thread)) return null
            BuzzLink.Message(channel, id.lowercase(), thread?.lowercase())
        }
        else -> null
    }
}

/** グループの共有リンク（kind 39000 の naddr）。リレーの鍵が分からないときは作れない。 */
fun groupShareLink(ref: GroupRef, relayPubkey: String): String =
    "nostr:" + encodeNaddr(GROUP_METADATA_KIND, relayPubkey, ref.groupId, listOf(ref.relayUrl))

/** 本文中のグループへのリンク（kind 39000 の naddr と、Buzz のメッセージ・チャンネルのリンク）。 */
data class GroupLinkMatch(val start: Int, val endExclusive: Int, val uri: String)

private val GROUP_NADDR_PATTERN = Regex("""nostr:(naddr1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)(\?invite=[A-Za-z0-9._~%-]+)?""", RegexOption.IGNORE_CASE)
// URL に使える ASCII だけ。日本語の文が空白なしで続いても、リンクに含めない。
private val BUZZ_LINK_PATTERN = Regex("""buzz://(?:message|channel)[A-Za-z0-9._~:/?#@!$&*+,;=%-]*""", RegexOption.IGNORE_CASE)

fun extractGroupLinks(text: String): List<GroupLinkMatch> {
    val naddrs = GROUP_NADDR_PATTERN.findAll(text).mapNotNull { match ->
        val address = decodeNaddr(match.groupValues[1]) ?: return@mapNotNull null
        if (address.kind != GROUP_METADATA_KIND) return@mapNotNull null
        GroupLinkMatch(match.range.first, match.range.last + 1, match.value)
    }
    val buzz = BUZZ_LINK_PATTERN.findAll(text).mapNotNull { match ->
        val value = match.value.trimEnd('.', ',', '。', '、', ')')
        if (parseBuzzLink(value) == null) return@mapNotNull null
        GroupLinkMatch(match.range.first, match.range.first + value.length, value)
    }
    return (naddrs + buzz).sortedBy { it.start }.toList()
}

/** 本文の `buzz://channel/…`（Markdown のリンク先も含む）が指すチャンネル ID。出現順に重複なく。 */
fun linkedBuzzChannels(text: String): List<String> =
    extractGroupLinks(text).mapNotNull { (parseBuzzLink(it.uri) as? BuzzLink.Channel)?.channelId }.distinct()

/** 参加シートの入力が何として読めたかの説明（種類 · ホスト）。読めないものは null。 */
fun describeGroupInput(input: GroupInput): String? {
    fun host(url: String) = url.substringAfter("://").trimEnd('/')
    return when (input) {
        is GroupInput.BuzzInvite -> "Buzz の招待 · ${host(input.relayUrl)}"
        is GroupInput.Group -> (if (input.inviteCode != null) "チャンネル（招待コードあり）" else "チャンネル") + " · ${host(input.ref.relayUrl)}"
        is GroupInput.Relay -> "リレーのチャンネル一覧 · ${host(input.relayUrl)}"
        GroupInput.BareBuzzCode, GroupInput.Invalid -> null
    }
}
