package com.nostr.torinos

internal actual fun takeLaunchInviteFragment(): String? = readAndClearLaunchInvite()

/** Android のタブレットもスマホと同じく、アプリへ案内する。 */
internal actual fun currentPhoneBrowser(): PhoneBrowser? {
    val userAgent = navigatorUserAgent()
    return when {
        "Android" in userAgent -> PhoneBrowser.Android
        "iPhone" in userAgent || "iPod" in userAgent -> PhoneBrowser.Ios
        else -> null
    }
}

/**
 * 新しいタブ（window.open）ではスキームのリンクが開かないことがあるので、今のページの移動として開く。
 * アプリが開けばページはそのまま残る。
 */
internal actual fun openAppLink(url: String) = setLocationHref(url)

private fun setLocationHref(url: String): Unit = js("{ window.location.href = url; }")

private fun readAndClearLaunchInvite(): String? =
    js("(() => { const v = window.__torinosLaunchInvite; delete window.__torinosLaunchInvite; return typeof v === 'string' ? v : null; })()")

private fun navigatorUserAgent(): String = js("navigator.userAgent || ''")
