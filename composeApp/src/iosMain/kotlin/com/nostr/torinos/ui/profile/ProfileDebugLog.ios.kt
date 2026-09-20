package com.nostr.torinos.ui.profile

import platform.Foundation.NSLog

internal actual fun profileDebugLog(message: String) {
    // NSLogの可変長引数はKotlin/Nativeから安全にブリッジできない(未定義動作/クラッシュの恐れ)。
    // フォーマット文字列を1つだけ渡す形にし、message中の"%"はフォーマット指定子と
    // 誤認されないようエスケープする。
    val sanitized = message.replace("%", "%%")
    NSLog("ProfileDebug: $sanitized")
}
