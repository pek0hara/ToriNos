package com.nostr.torinos.chat

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * ToriNos の招待リンク（docs/buzz-invite-link-design.md）。
 * 招待の画面で作って配るのは `torinos://join`（アプリで直接開く）。Web 版の URL は招待コードをフラグメント
 * （# の後ろ）に入れ、サーバーやアクセスログに出さない。どちらもコミュニティ（ホスト）と招待コードの組を持つ。
 */

/** Web 版の起点。 */
const val TORINOS_WEB_APP_URL = "https://pek0hara.github.io/ToriNos/app/"

private const val TORINOS_WEB_HOST = "pek0hara.github.io"
private val TORINOS_WEB_PATHS = setOf(
    "/ToriNos/app", "/ToriNos/app/", "/ToriNos/app/index.html",
    // 段階3の受け口（Universal Links / App Links）。
    "/ToriNos/join", "/ToriNos/join/",
)
private const val TORINOS_APP_SCHEME = "torinos://"
private const val MAX_INVITE_CODE_LENGTH = 256

/** Web 版の招待リンク。 */
fun toriNosInviteLink(relayUrl: String, code: String): String = TORINOS_WEB_APP_URL + "#" + joinQuery(relayUrl, code)

/** アプリで直接開く招待リンク。招待の画面で配るのはこの形。 */
fun toriNosAppInviteLink(relayUrl: String, code: String): String = TORINOS_APP_SCHEME + joinQuery(relayUrl, code)

/**
 * Android のブラウザから ToriNos アプリを開くリンク（配らない）。package を指定して、同じスキームを登録した
 * 別のアプリに渡らないようにする。アプリが無ければ Chrome は Google Play を開く。
 */
fun toriNosAndroidIntentLink(relayUrl: String, code: String): String =
    "intent://" + joinQuery(relayUrl, code) + "#Intent;scheme=torinos;package=$TORINOS_ANDROID_PACKAGE;end"

private const val TORINOS_ANDROID_PACKAGE = "com.nostr.torinos"

/** 発行した Buzz の招待から、配る招待リンク（`torinos://join?…`）を作る。 */
fun toriNosInviteLinkFor(invite: MintedInvite): String? {
    val host = invite.url.removePrefix("https://").substringBefore('/').takeIf { it.isNotEmpty() } ?: return null
    val relayUrl = relayUrlOf(host) ?: return null
    return toriNosAppInviteLink(relayUrl, invite.code)
}

fun isToriNosInviteLink(value: String): Boolean = parseToriNosInviteLink(value.trim()) is GroupInput.BuzzInvite

/** Web 版の URL のフラグメント（`#join?…`）だけを読む。招待でなければ null。 */
fun inviteFromFragment(fragment: String): GroupInput.BuzzInvite? = parseJoinQuery(fragment.removePrefix("#"))

/** 既定の公開リレーなら `wss://` を省いてホストだけにする（リンクを短くする）。 */
private fun joinQuery(relayUrl: String, code: String): String {
    val relay = if (relayUrl.startsWith("wss://", ignoreCase = true)) relayUrl.substring("wss://".length).trimEnd('/') else relayUrl.trimEnd('/')
    return "join?relay=" + relay.encodeURLParameter() + "&code=" + code.encodeURLParameter()
}

internal fun isToriNosLinkCandidate(value: String): Boolean =
    value.startsWith(TORINOS_APP_SCHEME, ignoreCase = true) ||
        value.startsWith("https://$TORINOS_WEB_HOST/", ignoreCase = true)

/** ToriNos の招待リンク。ToriNos の形でないもの、壊れているものは null。 */
internal fun parseToriNosInviteLink(value: String): GroupInput.BuzzInvite? {
    if (value.startsWith(TORINOS_APP_SCHEME, ignoreCase = true)) {
        val rest = value.substring(TORINOS_APP_SCHEME.length)
        return parseJoinQuery(if (rest.startsWith("join/?", ignoreCase = true)) "join?" + rest.substring("join/?".length) else rest)
    }
    if (!value.startsWith("https://", ignoreCase = true)) return null
    val beforeFragment = value.substringBefore('#')
    val fragment = value.substringAfter('#', missingDelimiterValue = "")
    // 招待コードがクエリ側にある形（サーバーやログに出る）は受け付けない。
    if ('?' in beforeFragment) return null
    val rest = beforeFragment.substring("https://".length)
    val host = rest.substringBefore('/')
    if (!host.equals(TORINOS_WEB_HOST, ignoreCase = true)) return null
    val path = rest.substring(host.length)
    if (path !in TORINOS_WEB_PATHS) return null
    return parseJoinQuery(fragment)
}

private fun parseJoinQuery(text: String): GroupInput.BuzzInvite? {
    if (!text.startsWith("join?", ignoreCase = true)) return null
    val params = queryParams(text.substring("join?".length))
    val relay = params["relay"]?.singleOrNull() ?: return null
    val code = params["code"]?.singleOrNull() ?: return null
    // 見た目の似た別のドメインに気づけないので、ASCII 以外のホストは受け付けない（IDN は xn-- の形で書く）。
    if (relay.any { it.code !in 0x21..0x7e }) return null
    if (code.isBlank() || code.length > MAX_INVITE_CODE_LENGTH) return null
    val relayUrl = relayUrlOf(relay) ?: return null
    return GroupInput.BuzzInvite(relayUrl, code)
}

/** ログイン待ちなどの間だけ預かる招待の期限。 */
const val PENDING_INVITE_TTL_MILLIS = 30 * 60 * 1000L

/** 預かっている招待。招待コードは文字列にしたときにも出さない（ログへの混入を防ぐ）。 */
class PendingInvite internal constructor(
    val relayUrl: String,
    private val code: String,
    val receivedAtMillis: Long,
) {
    val host: String get() = relayUrl.substringAfter("://").trimEnd('/')

    /** 参加シートに渡す形。 */
    internal fun link(): String = toriNosInviteLink(relayUrl, code)

    override fun toString(): String = "PendingInvite(host=$host)"
}

/**
 * 受け取った招待を、ログインや鍵の作成が終わるまでメモリにだけ預かる。
 * セッション（アカウント）の切り替えで画面が作り直されても残るよう、セッションの外に置く。
 */
class PendingInviteStore {
    private val _invite = MutableStateFlow<PendingInvite?>(null)
    val invite: StateFlow<PendingInvite?> = _invite.asStateFlow()

    /** 招待コード付きのリンクだけ預かる。預かれたら true。 */
    fun offer(text: String, nowMillis: Long): Boolean {
        val parsed = parseGroupInput(text) as? GroupInput.BuzzInvite ?: return false
        _invite.value = PendingInvite(parsed.relayUrl, parsed.code, nowMillis)
        return true
    }

    /**
     * OS から渡されたリンク（`torinos://join?…`、段階3では Web 版の招待 URL）を預かる。預かれたら true。
     * 貼り付けと違い、ToriNos の招待の形だけを受け付ける。リンクには招待コードが入るのでログに出さない。
     */
    fun offerAppLink(url: String, nowMillis: Long): Boolean {
        val parsed = parseToriNosInviteLink(url.trim()) ?: return false
        _invite.value = PendingInvite(parsed.relayUrl, parsed.code, nowMillis)
        return true
    }

    /** 期限内なら参加シートに渡すリンクを返し、預かりを消す。 */
    fun take(nowMillis: Long): String? {
        val current = _invite.value ?: return null
        _invite.value = null
        return current.link().takeIf { nowMillis - current.receivedAtMillis <= PENDING_INVITE_TTL_MILLIS }
    }

    fun clear() {
        _invite.value = null
    }
}

/** アプリ全体で1つの預かり場所。 */
val pendingInvites = PendingInviteStore()
