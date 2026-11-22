package com.nostr.torinos

/** Web 版を開いた URL にあった招待（`#join?…`）。一度読んだら消える。アプリでは常に null。 */
internal expect fun takeLaunchInviteFragment(): String?

/** スマホのブラウザの種類。PC・タブレット（iPad は Mac として名乗る）・アプリでは null。 */
internal enum class PhoneBrowser { Android, Ios }

internal expect fun currentPhoneBrowser(): PhoneBrowser?
