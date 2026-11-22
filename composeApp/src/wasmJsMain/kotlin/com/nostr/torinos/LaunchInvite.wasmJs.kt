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

private fun readAndClearLaunchInvite(): String? =
    js("(() => { const v = window.__torinosLaunchInvite; delete window.__torinosLaunchInvite; return typeof v === 'string' ? v : null; })()")

private fun navigatorUserAgent(): String = js("navigator.userAgent || ''")
