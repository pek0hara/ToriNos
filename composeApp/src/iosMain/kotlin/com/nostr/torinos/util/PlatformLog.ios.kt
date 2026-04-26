package com.nostr.torinos.util

actual fun platformLog(message: String) {
    // iOS Simulator で NSString ブリッジ中にクラッシュするため、ここでは無効化する。
    Unit
}
